# Lumora

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
# Build
./mvnw -DskipTests package

# Run
docker compose up
```

## Configuration

Key environment variables:

| Variable | Description |
|---|---|
| `WECHAT_APP_ID` | WeChat AppId |
| `WECHAT_TOKEN` | WeChat callback token |
| `WECHAT_AES_KEY` | WeChat AES encoding key |
| `MYSQL_HOST` | MySQL host |
| `MYSQL_PORT` | MySQL port |
| `MYSQL_DATABASE` | Database name |
| `MYSQL_USERNAME` | Database user |
| `MYSQL_PASSWORD` | Database password |
| `QQ_MAIL_USERNAME` | QQ email address |
| `QQ_MAIL_PASSWORD` | QQ email authorization code |
| `REPORT_RECIPIENTS` | Comma-separated report recipients |
| `LUMORA_MODE` | Startup mode: `serve`, `migrate`, or `schema-smoke` |

## License

MIT
