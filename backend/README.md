# Lumora · Backend

The backend half of the Lumora repo — see the [root README](../README.md) for the
overall layout, and [../frontend](../frontend) for the Astro site. All commands
below run from `backend/`.

WeChat Official Account daily reporting service. Receives WeChat callback events via the XML message callback protocol, stores them with deduplication, and generates daily email reports at 7:00 AM (Shanghai time) delivered through QQ SMTP.

## Tech Stack

- **Java 17** + **Spring Boot 3.3**
- **MyBatis** + **Flyway** (MySQL)
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

# Test (needs Docker: Testcontainers starts a MySQL instance)
mvn test

# Run as a container
docker build -t lumora-backend .
docker run --env-file .env -p 8080:8080 lumora-backend
```

Copy `.env.example` to `.env` and fill it in before running.

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
| `MYSQL_HOST` | — | MySQL host |
| `MYSQL_PORT` | `3306` | MySQL port |
| `MYSQL_DATABASE` | — | Database name |
| `MYSQL_USERNAME` | — | Application database user (DML only) |
| `MYSQL_PASSWORD` | — | Application database password |
| `MAIL_USERNAME` | — | QQ email address, also the From address |
| `MAIL_AUTH_CODE` | — | QQ email authorization code (not the account password) |
| `MAIL_FROM_NAME` | `Lumora` | Display name on outgoing mail |
| `REPORT_RECIPIENTS` | — | Comma-separated report recipients |
| `REPORT_ADMIN_KEY` | — | Key for `X-Lumora-Admin-Key` on `/internal/**` |
| `LUMORA_ZONE` | `Asia/Shanghai` | Zone for report dates, schedules and mail timestamps |
| `SCHEDULING_ENABLED` | `true` | Registers the 07:00 daily report job |
| `REPORT_RECOVERY_ENABLED` | `true` | Registers the stale-delivery recovery job |
| `RETENTION_ENABLED` | `true` | Registers the 03:30 retention job |
| `INTERNAL_SEND_ENABLED` | `true` | When false, `/internal/reports/{date}/send` returns 503 |
| `WORKER_READY_MARKER` | `/tmp/lumora-worker-ready` | File the worker readiness probe checks |
| `LUMORA_MODE` | `serve` | Startup mode: `serve`, `migrate`, or `schema-smoke` |
| `MIGRATION_MYSQL_USERNAME` | `MYSQL_USERNAME` | DDL user, used only by `LUMORA_MODE=migrate` |
| `MIGRATION_MYSQL_PASSWORD` | `MYSQL_PASSWORD` | Password for the DDL user |

The four `*_ENABLED` flags exist so a candidate container can serve callbacks
without competing for background work or sending mail. Turn them off on every
instance except the single active worker.

## Database Migration

The application never migrates implicitly — `spring.flyway.enabled` is `false`.
Run migrations as a separate one-shot container before releasing a new version:

```bash
docker run --rm --env-file .env -e LUMORA_MODE=migrate lumora-backend
docker run --rm --env-file .env -e LUMORA_MODE=schema-smoke lumora-backend
```

Migrations are expand-only: a release adds nullable columns, tables or
compatible indexes, and never drops, renames or changes the meaning of an
existing field. Cleanup migrations only land a release later, once the old code
has stopped and the rollback window has passed.

## Operating the Database

- Give the application user DML only (`SELECT`, `INSERT`, `UPDATE`, `DELETE`).
  DDL belongs to the separate migration user configured via
  `MIGRATION_MYSQL_USERNAME` / `MIGRATION_MYSQL_PASSWORD`.
- Encrypt the database volume and every backup. Keep backups 30 days.
- Rehearse restores on controlled infrastructure only. Never copy a production
  backup to a personal machine or any non-production environment.
- Operators access production through an audited account, temporarily — not
  through the application credentials.
- Retention runs daily: coordinates are nulled after 30 days, events, report
  snapshots and delivery audit rows are deleted after 400 days.

## License

MIT
