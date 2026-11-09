CREATE TABLE tidal_daily_quotas (
  day TEXT NOT NULL,
  key TEXT NOT NULL,
  n INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, key)
);

CREATE INDEX tidal_daily_quotas_day ON tidal_daily_quotas(day);
