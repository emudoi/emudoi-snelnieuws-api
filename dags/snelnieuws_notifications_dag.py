"""SnelNieuws push notifications.

Four DAGs in this file:

  - snelnieuws_notifications_prod              → fan-out to iOS (production
        APNs) + Android (FCM) in parallel. One trigger, both platforms.
        Triggered automatically by snelnieuws_notifications_auto_watcher
        whenever snelmind_summarize_today succeeds, and also available as
        a manual trigger from the Airflow UI.
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

  - snelnieuws_notifications_auto_watcher      → scheduled every 5 minutes.
        Polls the Airflow metastore for new successful runs of
        snelmind_summarize_today and triggers snelnieuws_notifications_prod
        when it sees one. Caps at 4 auto-triggers per day, keyed by the
        summarize run's logical date in Europe/Amsterdam. Manual runs of
        snelnieuws_notifications_prod do not count against the cap.

Idempotency: each dispatch endpoint records every call in its own table
(notification_dispatches for iOS, android_notification_dispatches for
Android) and bumps the per-tier `as_of_article_id`. A retry-after-success
is a safe no-op (count = 0).

Endpoint URLs are hardcoded — they're not secrets and don't vary across
environments. The shared X-API-Key is read from an Airflow Variable
(populated from Vault by emudoi-service-infra's vault-publish role):

  - `snelnieuws_notifications_api_key` → shared secret in X-API-Key header

Watcher state is kept in Airflow Variables:

  - `snelnieuws_last_seen_summarize_run_id`     → run_id of the most
        recent snelmind_summarize_today success we've processed. Seeded
        on first run so rollout doesn't trigger a backfire.
  - `snelnieuws_auto_trigger_count_YYYY_MM_DD`  → per-day auto-trigger
        counter, capped at 4. Keyed by Amsterdam logical date of the
        upstream summarize run, not by watcher tick time, so a summarize
        finishing at 23:58 counts against that day even if the watcher
        observes it at 00:02.
"""
import pendulum
import requests
from airflow.decorators import dag, task
from airflow.models import DagRun, Variable
from airflow.models.param import Param
from airflow.operators.trigger_dagrun import TriggerDagRunOperator
from airflow.utils.state import State

PROD_ENDPOINT_IOS         = "https://api.snel.emudoi.com/notifications/dispatch"
SANDBOX_ENDPOINT_IOS      = "https://api.snel.emudoi.com/notifications/dispatch-sandbox"
BROADCAST_ENDPOINT_IOS    = "https://api.snel.emudoi.com/notifications/broadcast"
DISPATCH_ENDPOINT_ANDROID = "https://api.snel.emudoi.com/android/notifications/dispatch"
BROADCAST_ENDPOINT_ANDROID = "https://api.snel.emudoi.com/android/notifications/broadcast"

DISPATCH_TIMEOUT_S = 60

UPSTREAM_DAG_ID         = "snelmind_summarize_today"
PROD_DAG_ID             = "snelnieuws_notifications_prod"
MAX_AUTO_TRIGGERS_PER_DAY = 4
AMSTERDAM = pendulum.timezone("Europe/Amsterdam")

VAR_LAST_SEEN_RUN_ID   = "snelnieuws_last_seen_summarize_run_id"
VAR_COUNTER_PREFIX     = "snelnieuws_auto_trigger_count_"  # + YYYY_MM_DD


def _post_dispatch(endpoint: str, frequency: str) -> dict:
    api_key = Variable.get("snelnieuws_notifications_api_key")
    params = {"frequency": int(frequency)} if frequency else {}
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
        "both platforms. Auto-triggered by snelnieuws_notifications_auto_watcher "
        "after snelmind_summarize_today succeeds; also available for manual runs."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    params={
        "frequency": Param(
            default="",
            type=["string"],
            enum=["", "1", "2", "3", "4"],
            description="Frequency tier (1-4) or empty to dispatch every tier.",
        ),
    },
    tags=["snelnieuws", "notifications"],
)
def snelnieuws_notifications_prod():
    @task
    def dispatch_ios(**context) -> dict:
        freq = (context["params"].get("frequency") or "").strip()
        return _post_dispatch(PROD_ENDPOINT_IOS, freq)

    @task(trigger_rule="all_done")
    def dispatch_android(**context) -> dict:
        freq = (context["params"].get("frequency") or "").strip()
        return _post_dispatch(DISPATCH_ENDPOINT_ANDROID, freq)

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
    params={
        "frequency": Param(
            default="",
            type=["string"],
            enum=["", "1", "2", "3", "4"],
            description="Frequency tier (1-4) or empty to dispatch every tier.",
        ),
    },
    tags=["snelnieuws", "notifications"],
)
def snelnieuws_notifications_sandbox():
    @task
    def dispatch(**context) -> dict:
        freq = (context["params"].get("frequency") or "").strip()
        return _post_dispatch(SANDBOX_ENDPOINT_IOS, freq)

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
    dag_id="snelnieuws_notifications_auto_watcher",
    description=(
        "Polls the Airflow metastore for new successful runs of "
        "snelmind_summarize_today and triggers snelnieuws_notifications_prod "
        "when it sees one. Caps at 4 auto-triggers per day."
    ),
    schedule="*/5 * * * *",
    start_date=pendulum.datetime(2026, 5, 15, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    tags=["snelnieuws", "notifications", "auto"],
)
def snelnieuws_notifications_auto_watcher():
    @task
    def detect_new_summarize_run() -> dict | None:
        last_seen = Variable.get(VAR_LAST_SEEN_RUN_ID, default_var="")

        runs = DagRun.find(dag_id=UPSTREAM_DAG_ID, state=State.SUCCESS)
        if not runs:
            return None
        latest = max(runs, key=lambda r: r.end_date or r.execution_date)

        if not last_seen:
            # First-deploy guard: seed the marker silently so rollout
            # doesn't fire on the most recent past success.
            Variable.set(VAR_LAST_SEEN_RUN_ID, latest.run_id)
            return None

        if latest.run_id == last_seen:
            return None

        logical_date_local = latest.execution_date.astimezone(AMSTERDAM)
        return {
            "run_id": latest.run_id,
            "counter_key": logical_date_local.strftime("%Y_%m_%d"),
        }

    @task.short_circuit
    def gate_by_daily_cap(summarize_run: dict | None) -> bool:
        if summarize_run is None:
            return False
        var_name = VAR_COUNTER_PREFIX + summarize_run["counter_key"]
        count = int(Variable.get(var_name, default_var="0"))
        return count < MAX_AUTO_TRIGGERS_PER_DAY

    @task
    def advance_marker(summarize_run: dict) -> None:
        var_name = VAR_COUNTER_PREFIX + summarize_run["counter_key"]
        count = int(Variable.get(var_name, default_var="0"))
        Variable.set(var_name, str(count + 1))
        Variable.set(VAR_LAST_SEEN_RUN_ID, summarize_run["run_id"])

    summarize = detect_new_summarize_run()
    gated = gate_by_daily_cap(summarize)

    trigger = TriggerDagRunOperator(
        task_id="trigger_prod_notifications",
        trigger_dag_id=PROD_DAG_ID,
        trigger_run_id=(
            "auto__{{ ti.xcom_pull(task_ids='detect_new_summarize_run')['run_id'] }}"
        ),
        conf={
            "source": "auto_watcher",
            "summarize_run_id": (
                "{{ ti.xcom_pull(task_ids='detect_new_summarize_run')['run_id'] }}"
            ),
        },
        reset_dag_run=False,
        wait_for_completion=False,
    )

    advance = advance_marker(summarize)
    gated >> trigger >> advance


snelnieuws_notifications_prod()
snelnieuws_notifications_sandbox()
snelnieuws_notifications_broadcast_manual()
snelnieuws_notifications_auto_watcher()
