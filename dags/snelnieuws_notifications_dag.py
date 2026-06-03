"""SnelNieuws push notifications.

Four DAGs in this file:

  - snelnieuws_notifications_prod              → fan-out to iOS (production
        APNs) + Android (FCM) in parallel. One trigger, both platforms.
        Triggered automatically by snelnieuws_notifications_slots at each
        daily slot (with a frequency threshold in conf), and also
        available as a manual trigger from the Airflow UI.
        iOS path: POST /notifications/dispatch — App Store + TestFlight
        builds whose tokens work against api.push.apple.com.
        Android path: POST /android/notifications/dispatch — every Android
        FCM subscriber (FCM has no sandbox/prod split).

  - snelnieuws_notifications_sandbox           → POST /notifications/dispatch-sandbox
        iOS-only. Sends to subscribers whose apns_environment='sandbox'
        (Xcode-debug installs that only work against
        api.sandbox.push.apple.com). FCM has no sandbox concept, so there
        is no Android counterpart on this DAG. Manual trigger only.

  - snelnieuws_notifications_broadcast_manual  → fan-out to iOS broadcast
        + Android broadcast in parallel. Each side has its own feature
        flag in `feature_flags`:
          test_notification       → iOS sandbox
          notify_applestore_apps  → iOS production
          notify_android          → all Android subscribers
        Flip rows in psql to enable/disable per side without redeploying.

  - snelnieuws_notifications_slots             → four scheduled fires per
        Amsterdam day at fixed slots — 07:00, 17:00, 19:00, 21:00 — with
        a silent window between 07:00 and 17:00. Each slot triggers
        snelnieuws_notifications_prod with a `frequency` threshold so a
        subscriber's picked frequency (1–4) decides which slots reach
        them, filling from the evening backward:

          slot   threshold (frequency >=)   reaches picks
          07:00  4                          4
          17:00  3                          3, 4
          19:00  2                          2, 3, 4
          21:00  1                          1, 2, 3, 4

        The dispatch endpoints still decide per language whether there is
        anything fresh to send (notification_candidates pool); a slot with
        no fresh candidate for any language sends nothing.

Content selection + dedup live entirely in the API: each dispatch builds a
per-language candidate pool from that language's recent articles and claims
the best unconsumed candidate, so an empty tick (no fresh candidate for any
language) just 503s with no_fresh_top_story and sends nothing.

Endpoint URLs are hardcoded — they're not secrets and don't vary across
environments. The shared X-API-Key is read from an Airflow Variable
(populated from Vault by emudoi-service-infra's vault-publish role):

  - `snelnieuws_notifications_api_key` → shared secret in X-API-Key header
"""
import pendulum
import requests
from airflow.decorators import dag, task
from airflow.models import Variable
from airflow.models.param import Param
from airflow.operators.trigger_dagrun import TriggerDagRunOperator

PROD_ENDPOINT_IOS         = "https://api.snel.emudoi.com/notifications/dispatch"
SANDBOX_ENDPOINT_IOS      = "https://api.snel.emudoi.com/notifications/dispatch-sandbox"
BROADCAST_ENDPOINT_IOS    = "https://api.snel.emudoi.com/notifications/broadcast"
DISPATCH_ENDPOINT_ANDROID = "https://api.snel.emudoi.com/android/notifications/dispatch"
BROADCAST_ENDPOINT_ANDROID = "https://api.snel.emudoi.com/android/notifications/broadcast"

DISPATCH_TIMEOUT_S = 60

PROD_DAG_ID = "snelnieuws_notifications_prod"
AMSTERDAM   = pendulum.timezone("Europe/Amsterdam")

# Fixed daily slots → frequency threshold passed to the dispatch endpoints.
# A subscriber whose picked frequency is >= the slot threshold is reached,
# so coverage fills from the evening backward (pick 1 → 21:00 only, pick 4 →
# every slot). The 07:00–17:00 gap is the daytime silent window.
SLOT_THRESHOLDS = {7: 4, 17: 3, 19: 2, 21: 1}


def _post_dispatch(endpoint: str, frequency=None) -> dict:
    api_key = Variable.get("snelnieuws_notifications_api_key")
    params = {}
    if frequency is not None and str(frequency).strip() != "":
        params["frequency"] = int(frequency)
    r = requests.post(
        endpoint,
        params=params,
        headers={"X-API-Key": api_key},
        timeout=DISPATCH_TIMEOUT_S,
    )
    r.raise_for_status()
    return r.json()


def _post_broadcast(endpoint: str, text: str) -> dict:
    api_key = Variable.get("snelnieuws_notifications_api_key")
    r = requests.post(
        endpoint,
        json={"text": text},
        headers={
            "X-API-Key": api_key,
            "Content-Type": "application/json",
        },
        timeout=DISPATCH_TIMEOUT_S,
    )
    r.raise_for_status()
    return r.json()


@dag(
    dag_id="snelnieuws_notifications_prod",
    description=(
        "Dispatch SnelNieuws push notifications. Fans out to iOS "
        "(production APNs) and Android (FCM) in parallel — one trigger, "
        "both platforms. Auto-triggered by snelnieuws_notifications_slots "
        "at each daily slot with a `frequency` threshold in conf; also "
        "available for manual runs (leave frequency empty to reach all)."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    params={
        "frequency": Param(
            default="",
            type="string",
            description=(
                "Slot threshold: only subscribers whose picked frequency is "
                ">= this value are notified. Empty → reach all frequencies."
            ),
        ),
    },
    tags=["snelnieuws", "notifications"],
)
def snelnieuws_notifications_prod():
    def _frequency(context) -> object:
        # conf wins (set by snelnieuws_notifications_slots); fall back to the
        # manual-trigger Param. Either may be "" / None → reach all.
        conf = context["dag_run"].conf or {}
        if "frequency" in conf:
            return conf.get("frequency")
        return (context["params"] or {}).get("frequency")

    @task
    def dispatch_ios(**context) -> dict:
        return _post_dispatch(PROD_ENDPOINT_IOS, _frequency(context))

    @task(trigger_rule="all_done")
    def dispatch_android(**context) -> dict:
        return _post_dispatch(DISPATCH_ENDPOINT_ANDROID, _frequency(context))

    dispatch_ios()
    dispatch_android()


@dag(
    dag_id="snelnieuws_notifications_sandbox",
    description=(
        "Manually dispatch iOS-sandbox push notifications (Xcode-debug "
        "installs). FCM has no sandbox split, so this DAG is iOS-only."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    tags=["snelnieuws", "notifications"],
)
def snelnieuws_notifications_sandbox():
    @task
    def dispatch() -> dict:
        return _post_dispatch(SANDBOX_ENDPOINT_IOS)

    dispatch()


@dag(
    dag_id="snelnieuws_notifications_broadcast_manual",
    description=(
        "Send a free-form push to whichever platform/environment is enabled "
        "in feature_flags. Fans out iOS and Android broadcasts in parallel."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    params={
        "text": Param(
            default="",
            type="string",
            minLength=1,
            description="The message body for the push (alert title is hardcoded to 'Snel Nieuws').",
        ),
    },
    tags=["snelnieuws", "notifications", "manual", "broadcast"],
)
def snelnieuws_notifications_broadcast_manual():
    @task
    def broadcast_ios(**context) -> dict:
        text = (context["params"].get("text") or "").strip()
        if not text:
            raise ValueError("text is required")
        return _post_broadcast(BROADCAST_ENDPOINT_IOS, text)

    @task(trigger_rule="all_done")
    def broadcast_android(**context) -> dict:
        text = (context["params"].get("text") or "").strip()
        if not text:
            raise ValueError("text is required")
        return _post_broadcast(BROADCAST_ENDPOINT_ANDROID, text)

    broadcast_ios()
    broadcast_android()


@dag(
    dag_id="snelnieuws_notifications_slots",
    description=(
        "Fires snelnieuws_notifications_prod at four fixed Amsterdam slots — "
        "07:00, 17:00, 19:00, 21:00 — each with a frequency threshold "
        "(07→4, 17→3, 19→2, 21→1) so a subscriber's picked frequency decides "
        "which slots reach them. Silent window between 07:00 and 17:00."
    ),
    schedule="0 7,17,19,21 * * *",
    start_date=pendulum.datetime(2026, 5, 15, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    tags=["snelnieuws", "notifications", "auto"],
)
def snelnieuws_notifications_slots():
    @task
    def resolve_threshold() -> int:
        # The DAG only fires at the configured slot hours; snap to the
        # nearest one so brief scheduler lag still maps to the right slot.
        hour = pendulum.now(AMSTERDAM).hour
        nearest = min(SLOT_THRESHOLDS, key=lambda h: abs(h - hour))
        return SLOT_THRESHOLDS[nearest]

    threshold = resolve_threshold()

    trigger = TriggerDagRunOperator(
        task_id="trigger_prod_notifications",
        trigger_dag_id=PROD_DAG_ID,
        trigger_run_id="slot__{{ ts_nodash }}",
        conf={
            "source": "slots",
            "frequency": "{{ ti.xcom_pull(task_ids='resolve_threshold') }}",
        },
        reset_dag_run=False,
        wait_for_completion=False,
    )

    threshold >> trigger


snelnieuws_notifications_prod()
snelnieuws_notifications_sandbox()
snelnieuws_notifications_broadcast_manual()
snelnieuws_notifications_slots()
