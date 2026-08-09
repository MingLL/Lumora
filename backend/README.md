# Lumora · Backend

The backend half of the Lumora repo — see the [root README](../README.md) for the
overall layout, and [../frontend](../frontend) for the Astro site. All commands
below run from `backend/`.

WeChat Official Account daily reporting service. Receives WeChat callback events via the XML message callback protocol, stores them with deduplication, and generates daily email reports at 7:00 AM (Shanghai time) delivered through QQ SMTP.

## Tech Stack

- **Java 17** + **Spring Boot 3.3**
- **MyBatis** + **Flyway** (PostgreSQL)
- **Testcontainers** for integration testing
- **Docker** containerization

## Features

- WeChat event ingestion (subscribe, unsubscribe, QR scan, menu click, location, etc.)
- Event normalization, fingerprinting, and cryptographic verification
- Deduplication and configurable data retention
- Daily report generation (HTML + plain text) with event breakdowns, unique user stats, QR scene analysis, menu interaction analytics
- Scheduled delivery at 7:00 AM (Shanghai timezone) via QQ SMTP with stale-delivery recovery
- Internal REST API (`/internal/reports/{date}/send`) for on-demand delivery and re-delivery
- Dockerized with multi-stage build

## Quick Start

```bash
# Build (no Maven wrapper in this repo — use a local mvn 3.9+)
mvn -DskipTests package

# Test (needs Docker: Testcontainers starts a PostgreSQL instance)
mvn test

# Run as a container. Tag it `lumora:local` -- that is the name `compose.yaml`
# looks for via `${LUMORA_IMAGE:-lumora:local}`.
docker build -t lumora:local .
docker run --env-file .env -p 8080:8080 lumora:local
```

Copy `.env.example` to `.env` and fill it in before running.

## Local WeChat Integration

End-to-end tests cover the callback flow offline. To test against the real
WeChat servers, use a [test account](https://mp.weixin.qq.com/debug/cgi-bin/sandbox)
plus a local HTTPS tunnel - no production environment needed:

1. Build the image once (compose expects the `lumora:local` tag):
   ```bash
   docker build -t lumora:local .
   ```
2. `cp .env.dev.example .env`, then fill in `WECHAT_APP_ID` from the test
   account page. Leave `WECHAT_ORIGINAL_ID` empty for now - step 4 captures it.
   The test account also shows an appSecret; this service never calls WeChat's
   outbound APIs, so there is nowhere to put it and nothing needs it.
3. Start the helper that captures the original ID, then expose it publicly.
   Both scripts live at the repo root, not under `backend/`:
   ```bash
   ../scripts/dev-wechat-original-id.py     # holds port 8080
   ../scripts/dev-wechat-tunnel.sh          # another terminal
   ```
4. In the test account backend, set the callback URL to
   `https://…trycloudflare.com/wechat/callback/{WECHAT_APP_ID}`, set Token to
   your `WECHAT_TOKEN`, and pick **plaintext mode** - the helper does not
   decrypt. Submit, then scan the test account QR code to follow it. The
   `subscribe` push prints the `gh_…` original ID; put it in `.env` and stop
   the helper with Ctrl-C.
5. Bring up the real stack. The tunnel keeps running and starts serving the
   `web` container instead of the helper, so the callback URL stays valid:
   ```bash
   docker compose --profile migrate up migrate
   docker compose up -d web
   ```

To confirm the whole path works, unfollow and re-follow the test account, then
check the events landed:

```bash
docker compose exec postgres psql -U lumora -d lumora \
  -c "SELECT id, event_type, raw_event, original_occurred_at FROM wechat_event ORDER BY id;"
```

Sending a chat message proves nothing here - `WechatEventNormalizer` only
accepts `MsgType=event`, so plain text is acknowledged and dropped without a
row or an INFO log. Use follow, unfollow, QR scans and menu clicks instead.

The `web` container already forces every `*_ENABLED` flag off, so a local
instance never sends mail or runs scheduled jobs.

The quick tunnel's hostname is random and dies with the process, so every
session needs the callback URL reconfigured. A Cloudflare account plus a named
tunnel gets you a stable address if that churn becomes annoying.

## Configuration

Key environment variables:

Everything below without a default is required — the application fails fast at
startup naming the missing variable. See `.env.example` for the full list.

| Variable | Default | Description |
|---|---|---|
| `WECHAT_APP_ID` | — | WeChat AppId; also the `{appId}` path segment of the callback URL |
| `WECHAT_ORIGINAL_ID` | — | Official Account original ID (`gh_…`), checked against `ToUserName` |
| `WECHAT_TOKEN` | — | WeChat callback token |
| `WECHAT_AES_KEY` | — | WeChat AES encoding key (safe mode) |
| `POSTGRES_HOST` | — | PostgreSQL host |
| `POSTGRES_PORT` | `5432` | PostgreSQL port |
| `POSTGRES_DATABASE` | — | Database name |
| `POSTGRES_USERNAME` | — | Application database user (DML only) |
| `POSTGRES_PASSWORD` | — | Application database password |
| `MAIL_USERNAME` | — | QQ email address, also the From address |
| `MAIL_AUTH_CODE` | — | QQ email authorization code (not the account password) |
| `MAIL_FROM_NAME` | `Lumora` | Display name on outgoing mail |
| `REPORT_RECIPIENTS` | — | Comma-separated report recipients |
| `REPORT_ADMIN_KEY` | — | Key for `X-Lumora-Admin-Key` on `/internal/**`; those routes also require `X-Request-Id` (see below) |
| `LUMORA_ZONE` | `Asia/Shanghai` | Zone for report dates, schedules and mail timestamps |
| `SCHEDULING_ENABLED` | `true` | Registers the 07:00 daily report job |
| `REPORT_RECOVERY_ENABLED` | `true` | Registers the stale-delivery recovery job |
| `RETENTION_ENABLED` | `true` | Registers the 03:30 retention job |
| `INTERNAL_SEND_ENABLED` | `true` | When false, `/internal/reports/{date}/send` returns 503 |
| `WORKER_READY_MARKER` | `/tmp/lumora-worker-ready` | File the worker readiness probe checks |
| `LUMORA_MODE` | `serve` | Startup mode: `serve`, `migrate`, or `schema-smoke` |
| `MIGRATION_POSTGRES_USERNAME` | `POSTGRES_USERNAME` | DDL user, used only by `LUMORA_MODE=migrate` |
| `MIGRATION_POSTGRES_PASSWORD` | `POSTGRES_PASSWORD` | Password for the DDL user |

The four `*_ENABLED` flags exist so a candidate container can serve callbacks
without competing for background work or sending mail. Turn them off on every
instance except the single active worker.

## Manual Report Delivery

`POST /internal/reports/{date}/send` regenerates and re-sends a day's report.
Two headers are mandatory, and `AdminKeyInterceptor` checks them in this order:

```bash
curl -X POST http://127.0.0.1:8081/internal/reports/2026-07-29/send \
     -H "X-Lumora-Admin-Key: $REPORT_ADMIN_KEY" \
     -H "X-Request-Id: $(uuidgen)"
```

- Missing or blank `X-Request-Id` → `400`, before the key is ever compared.
  A correct key does not help; the request id comes first.
- Missing or wrong `X-Lumora-Admin-Key` → `401`. The comparison is constant
  time, and a length mismatch still runs a dummy compare so response timing
  leaks nothing about the expected length.
- `INTERNAL_SEND_ENABLED=false` → `503`, regardless of headers. This is a
  separate gate from the key: `web` keeps it off, but `worker` runs with
  `INTERNAL_SEND_ENABLED=true` and binds to loopback only, so target it
  instead (the standalone `ops` container was merged into `worker` on
  2026-08-09):
  ```bash
  docker compose up -d worker
  ```

Everything under `/internal/**` is covered by the interceptor via a path
pattern, so new routes there inherit the protection - and anything outside that
prefix has none.

## Database Migration

The application never migrates implicitly — `spring.flyway.enabled` is `false`.
Run migrations as a separate one-shot container before releasing a new version:

```bash
docker run --rm --env-file .env -e LUMORA_MODE=migrate lumora:local
docker run --rm --env-file .env -e LUMORA_MODE=schema-smoke lumora:local
```

Migrations are expand-only: a release adds nullable columns, tables or
compatible indexes, and never drops, renames or changes the meaning of an
existing field. Cleanup migrations only land a release later, once the old code
has stopped and the rollback window has passed.

## Operating the Database

- Give the application user DML only (`SELECT`, `INSERT`, `UPDATE`, `DELETE`).
  DDL belongs to the separate migration user configured via
  `MIGRATION_POSTGRES_USERNAME` / `MIGRATION_POSTGRES_PASSWORD`.
- Encrypt the database volume and every backup. Keep backups 30 days. In
  production the data lives in a `hostPath` volume pinned to `dev2`, at
  `/opt/lumora/postgres` (see `deploy/k8s/lumora-postgres.yaml`) — back up
  that host/path, not `dev1`; the volume does not follow the pod if it is
  rescheduled. A logical backup:
  ```bash
  ssh dev1 "sudo /usr/local/bin/k3s kubectl -n lumora exec statefulset/lumora-postgres -- \
    pg_dump -U <POSTGRES_USERNAME> -d <POSTGRES_DATABASE> -Fc" > "lumora-$(date +%F).dump"
  ```
- Rehearse restores on controlled infrastructure only. Never copy a production
  backup to a personal machine or any non-production environment.
- Operators access production through an audited account, temporarily — not
  through the application credentials.
- Retention runs daily: coordinates are nulled after 30 days, events, report
  snapshots and delivery audit rows are deleted after 400 days.

## License

MIT
