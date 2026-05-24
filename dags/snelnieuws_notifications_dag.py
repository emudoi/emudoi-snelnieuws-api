"""SnelNieuws push notifications.

Four DAGs in this file:

  - snelnieuws_notifications_prod              → fan-out to iOS (production
        APNs) + Android (FCM) in parallel. One trigger, both platforms.
        Triggered automatically by snelnieuws_notifications_auto_watcher
        when its article-delta / quiet-hour / cooldown / daily-cap gates
        all pass, and also available as a manual trigger from the
        Airflow UI.
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
        Triggers snelnieuws_notifications_prod when ≥ 5 new summarized
        articles have appeared since the last fire, inside the
        07:00–19:00 Amsterdam active window, with ≥ 2 h between fires
        and ≤ 4 fires per Amsterdam day. Decoupled from
        snelmind_summarize_today — runs purely off the article-id delta
        polled from the ingestion API's
        /api/articles/summarized/last-id. Manual runs of
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

  - `snelnieuws_last_notified_article_id`       → pivot: the summarized
        article id at the time of the most recent auto-fire. Seeded to
        the current ingestion-api `last-id` on the first watcher tick
        after deploy so rollout doesn't fire on the historical backlog.
  - `snelnieuws_last_fired_at_utc`              → ISO-8601 UTC timestamp
        of the most recent auto-fire. Enforces the ≥ 2 h cooldown.
  - `snelnieuws_auto_trigger_count_YYYY_MM_DD`  → per-day auto-trigger
        counter, capped at 4. Keyed by the Amsterdam date of the fire
        time itself (not by any upstream summarize run).
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
INGESTION_LAST_ID_ENDPOINT = "https://ingestion.emudoi.com/api/articles/summarized/last-id"

DISPATCH_TIMEOUT_S = 60
LAST_ID_TIMEOUT_S = 15

PROD_DAG_ID               = "snelnieuws_notifications_prod"
MAX_AUTO_TRIGGERS_PER_DAY = 4
NEW_ARTICLES_THRESHOLD    = 5
ACTIVE_WINDOW_START_HOUR  = 7
ACTIVE_WINDOW_END_HOUR    = 19
MIN_COOLDOWN_HOURS        = 3  # 2026-05-24: was 2; spreads MAX_AUTO_TRIGGERS_PER_DAY=4 across the 12h ACTIVE_WINDOW (07-19 Amsterdam) instead of clustering them in the first 8h
AMSTERDAM = pendulum.timezone("Europe/Amsterdam")

VAR_LAST_NOTIFIED_ARTICLE_ID = "snelnieuws_last_notified_article_id"
VAR_LAST_FIRED_AT_UTC        = "snelnieuws_last_fired_at_utc"
VAR_COUNTER_PREFIX           = "snelnieuws_auto_trigger_count_"  # + YYYY_MM_DD


def _post_dispatch(endpoint: str) -> dict:
    api_key = Variable.get("snelnieuws_notifications_api_key")
    r = requests.post(
        endpoint,
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
        "on the article-id delta, inside the Amsterdam active window; "
        "also available for manual runs."
    ),
    schedule=None,
    start_date=pendulum.datetime(2026, 5, 1, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    tags=["snelnieuws", "notifications"],
)
def snelnieuws_notifications_prod():
    @task
    def dispatch_ios() -> dict:
        return _post_dispatch(PROD_ENDPOINT_IOS)

    @task(trigger_rule="all_done")
    def dispatch_android() -> dict:
        return _post_dispatch(DISPATCH_ENDPOINT_ANDROID)

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
    dag_id="snelnieuws_notifications_auto_watcher",
    description=(
        "Triggers snelnieuws_notifications_prod when ≥ 5 new summarized "
        "articles have appeared since the last fire, inside 07:00–19:00 "
        "Amsterdam, with ≥ 2 h between fires and ≤ 4 fires per day. "
        "Decoupled from snelmind_summarize_today — polls the ingestion "
        "API's article-id."
    ),
    schedule="*/5 * * * *",
    start_date=pendulum.datetime(2026, 5, 15, tz="Europe/Amsterdam"),
    catchup=False,
    max_active_runs=1,
    tags=["snelnieuws", "notifications", "auto"],
)
def snelnieuws_notifications_auto_watcher():
    @task.short_circuit
    def check_quiet_hours() -> bool:
        hour = pendulum.now(AMSTERDAM).hour
        return ACTIVE_WINDOW_START_HOUR <= hour < ACTIVE_WINDOW_END_HOUR

    @task.short_circuit
    def check_daily_cap() -> bool:
        today_key = pendulum.now(AMSTERDAM).strftime("%Y_%m_%d")
        count = int(Variable.get(VAR_COUNTER_PREFIX + today_key, default_var="0"))
        return count < MAX_AUTO_TRIGGERS_PER_DAY

    @task.short_circuit
    def check_cooldown() -> bool:
        last_fired_str = Variable.get(VAR_LAST_FIRED_AT_UTC, default_var="")
        if not last_fired_str:
            return True
        last_fired = pendulum.parse(last_fired_str)
        return (pendulum.now("UTC") - last_fired).total_seconds() >= MIN_COOLDOWN_HOURS * 3600

    @task.short_circuit
    def detect_new_articles() -> int | bool:
        r = requests.get(INGESTION_LAST_ID_ENDPOINT, timeout=LAST_ID_TIMEOUT_S)
        r.raise_for_status()
        current_id = int(r.json()["data"])

        last_notified_str = Variable.get(VAR_LAST_NOTIFIED_ARTICLE_ID, default_var="")
        if not last_notified_str:
            # First-deploy guard: seed the pivot silently so rollout
            # doesn't fire on the historical backlog.
            Variable.set(VAR_LAST_NOTIFIED_ARTICLE_ID, str(current_id))
            return False

        if current_id - int(last_notified_str) < NEW_ARTICLES_THRESHOLD:
            return False
        return current_id

    @task
    def advance_state(current_id: int) -> None:
        now_utc = pendulum.now("UTC")
        today_key = pendulum.now(AMSTERDAM).strftime("%Y_%m_%d")
        counter_var = VAR_COUNTER_PREFIX + today_key

        Variable.set(VAR_LAST_NOTIFIED_ARTICLE_ID, str(current_id))
        Variable.set(VAR_LAST_FIRED_AT_UTC, now_utc.isoformat())
        Variable.set(
            counter_var,
            str(int(Variable.get(counter_var, default_var="0")) + 1),
        )

    quiet_ok = check_quiet_hours()
    cap_ok = check_daily_cap()
    cool_ok = check_cooldown()
    current_id = detect_new_articles()

    trigger = TriggerDagRunOperator(
        task_id="trigger_prod_notifications",
        trigger_dag_id=PROD_DAG_ID,
        trigger_run_id=(
            "auto__article_{{ ti.xcom_pull(task_ids='detect_new_articles') }}"
            "__{{ ts_nodash }}"
        ),
        conf={
            "source": "auto_watcher",
            "as_of_article_id": (
                "{{ ti.xcom_pull(task_ids='detect_new_articles') }}"
            ),
        },
        reset_dag_run=False,
        wait_for_completion=False,
    )

    advance = advance_state(current_id)
    quiet_ok >> cap_ok >> cool_ok >> current_id >> trigger >> advance


snelnieuws_notifications_prod()
snelnieuws_notifications_sandbox()
snelnieuws_notifications_broadcast_manual()
snelnieuws_notifications_auto_watcher()
