-- Backend mirror of the Firebase Auth user. The PK is the Firebase uid, so
-- linking a notification subscription back to its owner is a single FK.
-- We never touch passwords; Firebase owns identity.
--
-- This table exists so a logged-in user installing on a 2nd device can
-- inherit their last frequency without redoing onboarding.

CREATE TABLE IF NOT EXISTS users (
    id          VARCHAR(128) PRIMARY KEY,
    email       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Anonymous (pre-login) device rows have NULL user_id. Once a token is
-- presented on POST /notifications/subscribe we set this. ON DELETE CASCADE
-- means a DELETE /users/me cleans up subscriptions in one shot.
ALTER TABLE notification_subscriptions
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(128) NULL
        REFERENCES users(id) ON DELETE CASCADE;

CREATE INDEX IF NOT EXISTS idx_notif_sub_user_id
    ON notification_subscriptions(user_id);
