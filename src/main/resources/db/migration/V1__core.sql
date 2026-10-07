CREATE TABLE users (
  id            uuid PRIMARY KEY,
  username      text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  timezone      text NOT NULL DEFAULT 'UTC',
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE follows (
  id          uuid PRIMARY KEY,
  user_id     uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind        text NOT NULL CHECK (kind IN ('league', 'team', 'game')),
  target_id   text NOT NULL,          -- NFL, mlb-chw, mlb-401907992
  league      text NOT NULL,          -- NFL, NBA, MLB, NHL
  alerts      jsonb NOT NULL,         -- {"GAME_START": {"enabled": true}, "END_OF_INNING": {"enabled": true, "options": {"innings": [9]}}}
  expires_at  timestamptz,            -- game follows only
  created_at  timestamptz NOT NULL DEFAULT now(),
  UNIQUE (user_id, kind, target_id)
);
CREATE INDEX follows_target ON follows (kind, target_id);

CREATE TABLE notifications (
  user_id     uuid NOT NULL,
  message_id  text NOT NULL,          -- <gameId>:v<version> from Stage 1
  created_at  timestamptz NOT NULL,   -- Kafka timestamp of the notifications record
  league      text NOT NULL,
  game_id     text,
  home_team   text,                   -- league-qualified, for the team filter
  away_team   text,
  body        jsonb NOT NULL,         -- the API's Notification object
  PRIMARY KEY (user_id, message_id)
);
CREATE INDEX notifications_by_time ON notifications (user_id, created_at DESC, message_id DESC);
CREATE INDEX notifications_by_game ON notifications (user_id, game_id, created_at DESC);

CREATE TABLE notification_reads (
  user_id      uuid PRIMARY KEY,
  last_seen_at timestamptz NOT NULL DEFAULT 'epoch',
  cleared_at   timestamptz NOT NULL DEFAULT 'epoch'
);
