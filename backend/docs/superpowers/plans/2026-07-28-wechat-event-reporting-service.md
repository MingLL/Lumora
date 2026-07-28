# WeChat Event Reporting Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a production-ready Spring Boot backend that records WeChat Official Account events, emails the previous day's report at 07:00 Asia/Shanghai through QQ SMTP, and deploys safely to dev2.

**Architecture:** A modular Spring Boot monolith separates WeChat protocol handling, normalized event persistence, report snapshots/delivery, mail transport, and operations. MySQL and Flyway provide durable state and concurrency constraints; scheduled jobs are lease-safe, disabled on candidate releases, and exposed only through internal services.

**Tech Stack:** Java 17, Spring Boot 3.3.x, Spring MVC, Validation, Mail, Actuator, MyBatis, Flyway, MySQL 8.4, Weixin Java MP 4.7.x, JUnit 5, AssertJ, Mockito, Testcontainers, Maven, Docker Compose.

**Design specification:** `docs/superpowers/specs/2026-07-28-wechat-event-reporting-service-design.md`

---

## File Map

- `pom.xml`: dependency and build configuration.
- `src/main/java/cn/minglli/lumora/LumoraApplication.java`: application entry point and scheduling.
- `src/main/java/cn/minglli/lumora/config/*`: typed environment configuration and validation.
- `src/main/java/cn/minglli/lumora/wechat/*`: callback controller, safe XML parsing, signature/encryption adapter, event mapper.
- `src/main/java/cn/minglli/lumora/event/*`: event domain model, deduplication, repository, retention.
- `src/main/java/cn/minglli/lumora/report/*`: aggregation, immutable snapshots, delivery leases, templates, scheduling, manual resend.
- `src/main/java/cn/minglli/lumora/mail/*`: transport abstraction and QQ SMTP adapter.
- `src/main/java/cn/minglli/lumora/operations/*`: admin-key authentication and endpoint isolation.
- `src/main/resources/db/migration/*`: expand-only production schema.
- `src/test/java/cn/minglli/lumora/**/*`: unit, MVC, integration, concurrency, and lifecycle tests.
- `Dockerfile`, `compose.yaml`, `deploy/*`: container build, local stack, and blue/green dev2 operations.
- `.env.example`, `README.md`: safe configuration and operator instructions.

## Task 1: Bootstrap the Application and Validated Configuration

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/cn/minglli/lumora/LumoraApplication.java`
- Create: `src/main/java/cn/minglli/lumora/config/LumoraProperties.java`
- Create: `src/main/java/cn/minglli/lumora/config/ClockConfiguration.java`
- Create: `src/main/resources/application.yml`
- Create: `src/test/java/cn/minglli/lumora/config/LumoraPropertiesTest.java`
- Create: `.gitignore`
- Create: `.env.example`

- [x] **Step 1: Write failing configuration binding tests**

Test that missing `WECHAT_APP_ID`, `WECHAT_ORIGINAL_ID`, `WECHAT_TOKEN`, `WECHAT_AES_KEY`, MySQL settings, QQ mail settings, recipients, or admin key yields a named validation error; test recipient parsing and default `Asia/Shanghai`.

- [x] **Step 2: Run the focused test and confirm it fails**

Run: `mvn -q -Dtest=LumoraPropertiesTest test`  
Expected: FAIL because the application and typed properties do not exist.

- [x] **Step 3: Add the minimal Spring Boot skeleton**

Use Java 17 and dependency management for Web, Validation, Mail, Actuator, MyBatis, Flyway, MySQL, Weixin Java MP, Testcontainers, and test support. Bind secrets from environment placeholders only. Inject a `Clock` so time-dependent code is deterministic.

- [x] **Step 4: Run bootstrap tests**

Run: `mvn -q -Dtest=LumoraPropertiesTest test`  
Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add pom.xml src .gitignore .env.example
git commit -m "feat: bootstrap validated Lumora service"
```

## Task 2: Create the Expand-Only Database Schema and Event Repository

**Files:**
- Create: `src/main/resources/db/migration/V1__create_event_and_report_tables.sql`
- Create: `src/main/java/cn/minglli/lumora/event/EventType.java`
- Create: `src/main/java/cn/minglli/lumora/event/WechatEvent.java`
- Create: `src/main/java/cn/minglli/lumora/event/WechatEventMapper.java`
- Create: `src/main/resources/mapper/WechatEventMapper.xml`
- Create: `src/main/java/cn/minglli/lumora/event/WechatEventRepository.java`
- Create: `src/test/java/cn/minglli/lumora/support/MySqlContainerTest.java`
- Create: `src/test/java/cn/minglli/lumora/event/WechatEventRepositoryTest.java`

- [x] **Step 1: Write failing MySQL integration tests**

Assert Flyway creates `wechat_event`, `daily_report`, and `report_delivery_attempt`; verify UTC `timestamp(6)`, `unique(app_id,deduplication_key)`, `unique(report_date,version)`, nullable generated `auto_report_id`, and manual request uniqueness.

- [x] **Step 2: Run and confirm schema tests fail**

Run: `mvn -q -Dtest=WechatEventRepositoryTest test`  
Expected: FAIL because migration and repository are missing.

- [x] **Step 3: Implement V1 and insert semantics**

Use an insert that returns `INSERTED` or `DUPLICATE` without treating a duplicate key as an application error. Store all instants in UTC. Keep raw XML and message content out of the table.

- [x] **Step 4: Test persistence and idempotency**

Run: `mvn -q -Dtest=WechatEventRepositoryTest test`  
Expected: PASS, including concurrent duplicate insert producing one row.

- [x] **Step 5: Commit**

```bash
git add src/main/resources/db src/main/resources/mapper src/main/java/cn/minglli/lumora/event src/test
git commit -m "feat: add durable event and report schema"
```

## Task 3: Normalize and Fingerprint WeChat Events

**Files:**
- Create: `src/main/java/cn/minglli/lumora/wechat/WechatInboundMessage.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/WechatEventNormalizer.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/EventDeduplicationKey.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/SafeMessageSummary.java`
- Create: `src/test/java/cn/minglli/lumora/wechat/WechatEventNormalizerTest.java`
- Create: `src/test/java/cn/minglli/lumora/wechat/EventDeduplicationKeyTest.java`

- [x] **Step 1: Write table-driven failing mapping tests**

Cover `subscribe`, QR subscribe, `unsubscribe`, `SCAN`, `LOCATION`, `CLICK`, `VIEW`, six exact `MENU_OTHER` event names, unsupported event to `UNKNOWN`, and ordinary non-event messages to `IGNORED`.

- [x] **Step 2: Add failing fingerprint and privacy tests**

Verify `qrscene_` normalization; length-prefixed null-safe serialization; `msgid:` preference; SHA-256 stability; composite-field fingerprints distinguish payloads without persisting raw scan, photo, or selected-location data; summaries contain only whitelisted metadata.

- [x] **Step 3: Run and confirm failures**

Run: `mvn -q -Dtest=WechatEventNormalizerTest,EventDeduplicationKeyTest test`  
Expected: FAIL because normalization classes are missing.

- [x] **Step 4: Implement the minimal pure domain functions**

Keep mapping and fingerprinting free of Spring and database dependencies. Make normalization locale-independent and deterministic.

- [x] **Step 5: Run focused and property-style edge tests**

Run: `mvn -q -Dtest=WechatEventNormalizerTest,EventDeduplicationKeyTest test`  
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/cn/minglli/lumora/wechat src/test/java/cn/minglli/lumora/wechat
git commit -m "feat: normalize and fingerprint WeChat events"
```

## Task 4: Implement Secure WeChat Callback Protocols

**Files:**
- Create: `src/main/java/cn/minglli/lumora/wechat/WechatProtocolService.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/SafeXmlParser.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/WechatEventIngestionService.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/WechatCallbackController.java`
- Create: `src/main/java/cn/minglli/lumora/wechat/WechatCallbackExceptionHandler.java`
- Create: `src/test/java/cn/minglli/lumora/wechat/SafeXmlParserTest.java`
- Create: `src/test/java/cn/minglli/lumora/wechat/WechatCallbackControllerTest.java`

- [x] **Step 1: Write failing GET callback MVC tests**

Cover valid verification, invalid signature, wrong route AppID, unchanged `echostr`, and `text/plain`.

- [x] **Step 2: Write failing POST callback tests**

Cover plaintext and encrypted fixtures, original-ID mismatch, encrypted-envelope AppID mismatch, bad `msg_signature`, corrupt ciphertext, malformed XML, XXE, 256 KiB limit, ignored ordinary messages, duplicate success, and repository failure returning a retryable non-2xx response.

- [x] **Step 3: Run and confirm protocol failures**

Run: `mvn -q -Dtest=SafeXmlParserTest,WechatCallbackControllerTest test`  
Expected: FAIL because callback components are missing.

- [x] **Step 4: Implement signature/decryption adapter and safe parsing**

Delegate WeChat cryptography to Weixin Java MP. Enforce route identity before parsing, original ID after parsing, and literal `success` only after a durable insert or duplicate result.

- [x] **Step 5: Run callback tests**

Run: `mvn -q -Dtest=SafeXmlParserTest,WechatCallbackControllerTest test`  
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/cn/minglli/lumora/wechat src/test/java/cn/minglli/lumora/wechat
git commit -m "feat: accept secure WeChat event callbacks"
```

## Task 5: Aggregate and Render Immutable Daily Reports

**Files:**
- Create: `src/main/java/cn/minglli/lumora/report/DailyReportSnapshot.java`
- Create: `src/main/java/cn/minglli/lumora/report/ReportWindow.java`
- Create: `src/main/java/cn/minglli/lumora/report/DailyReportMapper.java`
- Create: `src/main/resources/mapper/DailyReportMapper.xml`
- Create: `src/main/java/cn/minglli/lumora/report/DailyReportService.java`
- Create: `src/main/java/cn/minglli/lumora/report/ReportTemplateRenderer.java`
- Create: `src/test/java/cn/minglli/lumora/report/DailyReportServiceTest.java`
- Create: `src/test/java/cn/minglli/lumora/report/ReportTemplateRendererTest.java`

- [x] **Step 1: Write failing timezone and aggregation tests**

Use a fixed clock to verify Shanghai yesterday maps to the correct UTC half-open range. Assert totals and unique users include `UNKNOWN`; subscribe/unsubscribe/net event counts; per-type details; QR/menu counts plus unique users; missing-label behavior; anomalous timestamps; and empty-day output.

- [x] **Step 2: Write failing immutable snapshot race tests**

Two concurrent version-1 creators must converge on one stored JSON snapshot. Regeneration creates version N+1 without changing prior versions.

- [x] **Step 3: Run report tests and confirm failure**

Run: `mvn -q -Dtest=DailyReportServiceTest,ReportTemplateRendererTest test`  
Expected: FAIL because aggregation and templates are missing.

- [x] **Step 4: Implement SQL aggregation and renderers**

Return both HTML and plain text. Do not include coordinates or full OpenIDs. Include stable report date, version, and generation time.

- [x] **Step 5: Run focused report tests**

Run: `mvn -q -Dtest=DailyReportServiceTest,ReportTemplateRendererTest test`  
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/cn/minglli/lumora/report src/main/resources/mapper src/test/java/cn/minglli/lumora/report
git commit -m "feat: aggregate immutable daily reports"
```

## Task 6: Add Lease-Safe QQ Mail Delivery and Scheduling

**Files:**
- Create: `src/main/java/cn/minglli/lumora/mail/MailGateway.java`
- Create: `src/main/java/cn/minglli/lumora/mail/QqSmtpMailGateway.java`
- Create: `src/main/java/cn/minglli/lumora/report/ReportDeliveryMapper.java`
- Create: `src/main/resources/mapper/ReportDeliveryMapper.xml`
- Create: `src/main/java/cn/minglli/lumora/report/ReportDeliveryService.java`
- Create: `src/main/java/cn/minglli/lumora/report/DailyReportScheduler.java`
- Create: `src/test/java/cn/minglli/lumora/report/ReportDeliveryServiceTest.java`
- Create: `src/test/java/cn/minglli/lumora/report/DailyReportSchedulerTest.java`

- [x] **Step 1: Write failing lease and uniqueness tests**

Cover concurrent AUTO get-or-create, one winning claimant, stale `SENDING` recovery after 10 minutes, manual request idempotency, active-delivery 409 semantics, and forced delivery audit rows.

- [x] **Step 2: Write failing SMTP state-machine tests**

Cover success; transient failure with 5/30-second injected backoff; permanent auth failure; three total attempts; sanitized 500-character errors; stable `Message-ID`; and the SMTP-accepted-before-`SENT` crash window documenting at-least-once behavior.

- [x] **Step 3: Run and confirm failures**

Run: `mvn -q -Dtest=ReportDeliveryServiceTest,DailyReportSchedulerTest test`  
Expected: FAIL because delivery services are missing.

- [x] **Step 4: Implement the state machine and report schedulers**

Schedule daily reporting at `0 0 7 * * *` in `Asia/Shanghai`. Register daily and recovery jobs only when their enable flags are true. Retention scheduling is introduced with `EventRetentionService` in Task 7. Configure QQ SMTP SSL 465 and 10-second connection/read/write timeouts.

- [x] **Step 5: Run delivery tests**

Run: `mvn -q -Dtest=ReportDeliveryServiceTest,DailyReportSchedulerTest test`  
Expected: PASS without sending real email.

- [x] **Step 6: Commit**

```bash
git add src/main/java/cn/minglli/lumora/mail src/main/java/cn/minglli/lumora/report src/main/resources/mapper src/test
git commit -m "feat: deliver lease-safe QQ email reports"
```

## Task 7: Add Protected Manual Resend and Retention

**Files:**
- Create: `src/main/java/cn/minglli/lumora/operations/AdminKeyInterceptor.java`
- Create: `src/main/java/cn/minglli/lumora/operations/WebSecurityConfiguration.java`
- Create: `src/main/java/cn/minglli/lumora/report/ManualReportController.java`
- Create: `src/main/java/cn/minglli/lumora/event/EventRetentionService.java`
- Create: `src/test/java/cn/minglli/lumora/report/ManualReportControllerTest.java`
- Create: `src/test/java/cn/minglli/lumora/event/EventRetentionServiceTest.java`

- [x] **Step 1: Write failing admin endpoint tests**

Require constant-time `X-Lumora-Admin-Key` checking and `X-Request-Id`; reject missing/invalid credentials, today/future dates, duplicate active sends, and unrequested resends; test `force` and `regenerate`.

- [x] **Step 2: Write failing retention tests**

At 30 days, coordinates become null. At 400 days, events, report snapshots, and delivery audit records are removed. Verify boundaries and logs contain counts only.

- [x] **Step 3: Run and confirm failures**

Run: `mvn -q -Dtest=ManualReportControllerTest,EventRetentionServiceTest test`  
Expected: FAIL.

- [x] **Step 4: Implement protected operations**

Do not expose `/internal/**`, readiness, or sensitive Actuator endpoints through public deployment ingress. Only liveness is public. Disable internal send behavior entirely in candidate mode. Add the retention scheduler at `0 30 3 * * *` in `Asia/Shanghai`, conditional on `RETENTION_ENABLED`.

- [x] **Step 5: Run focused tests**

Run: `mvn -q -Dtest=ManualReportControllerTest,EventRetentionServiceTest test`  
Expected: PASS.

- [x] **Step 6: Commit**

```bash
git add src/main/java/cn/minglli/lumora/operations src/main/java/cn/minglli/lumora/report src/main/java/cn/minglli/lumora/event src/test
git commit -m "feat: protect report operations and enforce retention"
```

## Task 8: Package and Document the Local Production Stack

**Files:**
- Create: `Dockerfile`
- Create: `.dockerignore`
- Create: `compose.yaml`
- Create: `deploy/nginx/lumora.conf.template`
- Create: `deploy/check-env.sh`
- Create: `README.md`
- Modify: `.env.example`

- [x] **Step 1: Write container and configuration smoke assertions**

Add a Maven context test for production startup with mail replaced by a no-op profile. Add `docker compose config` and shell syntax checks to the verification script.

- [x] **Step 2: Implement a non-root multi-stage image and Compose stack**

Pin MySQL 8.4, persist its data, add application/MySQL health checks, avoid exposing MySQL publicly, and keep secrets out of build arguments and image layers. Flyway migration runs as an explicit one-shot service.

- [x] **Step 3: Validate JVM and Compose packaging**

Run: `mvn -q verify`  
Expected: PASS.

Run: `docker compose --env-file .env.example config --quiet`  
Expected: exit 0 without printing secret values.

Run: `bash -n deploy/check-env.sh`  
Expected: exit 0.

- [x] **Step 4: Build the image**

Run: `docker build -t lumora:test .`  
Expected: build succeeds and image user is non-root.

- [x] **Step 5: Inspect the built image**

Run: `bash deploy/verify-packaging.sh lumora:test`  
Expected: exit 0; image runs as non-root and contains no secret-shaped build metadata.

- [x] **Step 6: Commit**

```bash
git add Dockerfile .dockerignore compose.yaml deploy README.md .env.example src/test
git commit -m "build: package Lumora production stack"
```

## Task 9: Inspect dev2 and Add Safe Blue/Green Deployment Automation

> **实现偏离**（2026-07-29）：本任务原计划在 dev2 上跑一套独立的 Docker Compose +
> nginx 蓝绿。实际 dev1/dev2 跑的是 k3s + Traefik，前端已经在用，所以改为复用现有集群。
> 蓝绿切换由 k8s 的滚动更新承担（web 用 `maxUnavailable: 0`，worker 用 `Recreate`
> 保证任一时刻最多一个调度实例），不再手写 activate/rollback 脚本 —— 回滚就是
> `kubectl rollout undo`。计划里逐条的顺序与安全性要求原样保留，由契约测试锁住。
>
> **实际产出：**
> - `deploy/k8s/lumora-backend.yaml` —— web/worker/ops 三个 Deployment + Service + Ingress
> - `deploy/k8s/lumora-backend-migrate.yaml` —— 一次性迁移 Job，按版本命名
> - `deploy/k8s/lumora-mysql.yaml` —— 可选的集群内 MySQL
> - `deploy/deploy-backend.sh` —— 构建 → ctr import → Secret → 迁移 → smoke → apply → 验证
> - `deploy/tests/deploy_contract_test.sh` —— 14 条顺序/回滚/不泄密断言
>
> `compose.yaml` 仍然保留，用于本地跑一套生产形态的栈。

- [ ] **Step 1: Perform read-only dev2 discovery**

Run through SSH: inspect OS, Docker/Compose, listening ports, disk, current proxy, existing Lumora paths, and service ownership. Do not create files, restart services, or print environment values.

- [x] **Step 2: Write shell contract tests**

Create `deploy/tests/deploy_contract_test.sh` with fake `docker`, `curl`, SSH, and proxy commands on `PATH`. Assert candidate web starts with all background flags false; signed GET and POST acceptance occur before worker cutover; proxy switches only after health; rollback retains current service; scripts never echo `.env`.

- [x] **Step 3: Implement versioned blue/green scripts**

Require an explicit immutable image tag, explicit deploy directory, and validated server `.env`. Run expand-only migration once, start candidate, verify locally, switch upstream atomically, activate background work, and retain the previous version for rollback.

- [x] **Step 4: Run local deployment checks**

Run: `bash -n deploy/dev2/*.sh`  
Expected: all scripts parse.

Run: `bash deploy/tests/deploy_contract_test.sh`  
Expected: all ordering, rollback, and secret-output assertions pass.

Run: `mvn -q verify`  
Expected: PASS.

- [ ] **Step 5: Deploy to dev2 when production values are available**

Required external inputs: public HTTPS callback hostname, WeChat AppID/original ID/token/AES key, QQ mailbox/auth code/recipients, admin key, and approved server path/port. Deployment must stop before mutation if any input or preflight check is missing.

- [ ] **Step 6: Execute dev2 acceptance**

Verify public GET signature, a signed idempotent simulated POST, SMTP smoke mail, Shanghai 07:00 scheduling, MySQL persistence across restart, public denial of internal endpoints, and rollback with a deliberately unhealthy candidate. External smoke actions require explicit production-test configuration.

- [ ] **Step 7: Commit**

```bash
git add deploy/dev2 README.md
git commit -m "ops: add safe dev2 blue-green deployment"
```

## Task 10: Final Verification and Security Review

**Files:**
- Modify as required by verification findings only.

- [x] **Step 1: Run the complete automated suite**

Run: `mvn -q clean verify`  
Expected: PASS with no skipped required tests.

- [x] **Step 2: Run packaging checks**

Run: `docker compose --env-file .env.example config --quiet`  
Expected: exit 0.

Run: `docker build -t lumora:verify .`  
Expected: exit 0.

- [x] **Step 3: Scan tracked content for credentials**

Run: `git grep -n -E '(sk-[A-Za-z0-9]{20,}|MAIL_AUTH_CODE=.+|MYSQL_PASSWORD=.+|WECHAT_AES_KEY=.+)' -- ':!docs/superpowers/*'`  
Expected: no real credential values.

- [x] **Step 4: Review the final diff and status**

Run: `git status --short` and `git log --oneline --decorate -12`  
Expected: only intentional changes; task commits are present.

- [ ] **Step 5: Request code review**

Invoke `superpowers:requesting-code-review`; resolve blocking findings and repeat verification.

- [ ] **Step 6: Commit final fixes, if any**

```bash
git add <only reviewed files>
git commit -m "fix: address final Lumora verification findings"
```

---

## Exact Implementation Contracts

The contracts below are normative for Tasks 1–10. Tests must encode these values rather than restating them in prose.

### Maven and Runtime Contract

`pom.xml` must set Java 17, Spring Boot `3.3.13`, Weixin Java MP `4.7.0`, MyBatis starter `3.0.4`, and Testcontainers BOM `1.20.6`. Include:

```xml
<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-mail</artifactId></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
  <dependency><groupId>org.mybatis.spring.boot</groupId><artifactId>mybatis-spring-boot-starter</artifactId><version>3.0.4</version></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
  <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
  <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
  <dependency><groupId>com.github.binarywang</groupId><artifactId>weixin-java-mp</artifactId><version>4.7.0</version></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>mysql</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
</dependencies>
```

`application.yml` must include:

```yaml
spring:
  flyway:
    enabled: false
  datasource:
    url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT:3306}/${MYSQL_DATABASE}?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true
  mail:
    host: smtp.qq.com
    port: 465
    properties:
      mail.smtp.ssl.enable: true
      mail.smtp.connectiontimeout: 10000
      mail.smtp.timeout: 10000
      mail.smtp.writetimeout: 10000
lumora:
  scheduling-enabled: ${SCHEDULING_ENABLED:true}
  report-recovery-enabled: ${REPORT_RECOVERY_ENABLED:true}
  retention-enabled: ${RETENTION_ENABLED:true}
  internal-send-enabled: ${INTERNAL_SEND_ENABLED:true}
```

The Compose `migrate` service runs the same image with `spring.flyway.enabled=true` and a migrate-only application mode. Normal and candidate application containers never migrate implicitly. `LumoraPropertiesTest` asserts `MAIL_FROM_NAME` defaults to `Lumora`, all four candidate safeguards bind, and a JDBC integration assertion executes `SELECT @@session.time_zone` expecting `+00:00`.

### Exact V1 DDL

Task 2 must begin with this schema contract, with InnoDB and `utf8mb4`:

```sql
CREATE TABLE wechat_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  app_id VARCHAR(64) NOT NULL,
  open_id VARCHAR(128) NOT NULL,
  event_type VARCHAR(32) NOT NULL,
  raw_msg_type VARCHAR(32) NOT NULL,
  raw_event VARCHAR(64) NULL,
  message_id BIGINT NULL,
  original_occurred_at TIMESTAMP(6) NOT NULL,
  effective_occurred_at TIMESTAMP(6) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL,
  anomalous_timestamp BOOLEAN NOT NULL DEFAULT FALSE,
  deduplication_key VARCHAR(71) NOT NULL,
  raw_event_key VARCHAR(512) NULL,
  qr_scene VARCHAR(512) NULL,
  ticket VARCHAR(512) NULL,
  ticket_present BOOLEAN NOT NULL DEFAULT FALSE,
  menu_key VARCHAR(512) NULL,
  menu_url VARCHAR(2048) NULL,
  latitude DECIMAL(10,7) NULL,
  longitude DECIMAL(10,7) NULL,
  location_precision DECIMAL(12,6) NULL,
  composite_type VARCHAR(32) NULL,
  composite_item_count INT NULL,
  composite_sha256 CHAR(64) NULL,
  safe_summary JSON NOT NULL,
  normalized_message_sha256 CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_event_dedup (app_id, deduplication_key),
  KEY ix_event_report (effective_occurred_at, event_type),
  KEY ix_event_user (open_id, effective_occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE daily_report (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  report_date DATE NOT NULL,
  version INT NOT NULL,
  window_start TIMESTAMP(6) NOT NULL,
  window_end TIMESTAMP(6) NOT NULL,
  data_cutoff_at TIMESTAMP(6) NOT NULL,
  snapshot_json JSON NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_report_version (report_date, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_delivery_attempt (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  delivery_id CHAR(36) NOT NULL,
  report_id BIGINT UNSIGNED NOT NULL,
  trigger_type VARCHAR(16) NOT NULL,
  request_id VARCHAR(128) NULL,
  auto_report_id BIGINT UNSIGNED
    GENERATED ALWAYS AS (CASE WHEN trigger_type='AUTO' THEN report_id ELSE NULL END) STORED,
  status VARCHAR(16) NOT NULL,
  recipient_masked VARCHAR(1024) NOT NULL,
  recipient_sha256 CHAR(64) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  claimed_at TIMESTAMP(6) NULL,
  lease_until TIMESTAMP(6) NULL,
  completed_at TIMESTAMP(6) NULL,
  last_error_class VARCHAR(128) NULL,
  last_error_summary VARCHAR(500) NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uq_delivery_id (delivery_id),
  UNIQUE KEY uq_auto_report (auto_report_id),
  UNIQUE KEY uq_manual_request (report_id, request_id),
  CONSTRAINT fk_delivery_report FOREIGN KEY (report_id) REFERENCES daily_report(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Task 2 tests assert every column/index through `information_schema`, not only table existence.
Repository tests also round-trip the bounded QR `ticket`. It follows the 400-day event retention; `safe_summary` records only `ticket_present`, never the value.

### Migration and Compatibility Modes

Create:

- `src/main/java/cn/minglli/lumora/operations/StartupModeRunner.java`
- `src/test/java/cn/minglli/lumora/operations/MigrationModeTest.java`
- `deploy/compatibility-smoke.sh`

The packaged application accepts exactly three startup modes through `LUMORA_MODE`:

- `serve` (default): normal web or worker behavior, with `spring.flyway.enabled=false`.
- `migrate`: forces non-web mode and all four background flags false, obtains the separately configured migration datasource, calls `Flyway.configure().dataSource(...).locations("classpath:db/migration").load().migrate()`, then exits 0. Migration validation/failure exits nonzero. No web server, mail gateway, scheduler, retention, or callback bean may start.
- `schema-smoke`: forces non-web mode and all background flags false, uses the normal least-privilege datasource to execute read-only selects against every required table/column, then exits 0; incompatibility exits nonzero.

`MigrationModeTest` starts the packaged Spring context against Testcontainers MySQL, asserts V1 is applied, no web port opens, no scheduled task exists, no event/report row changes beyond Flyway history, and the process exit-code adapter receives 0. A failing migration fixture must produce nonzero.

Compose uses:

```yaml
migrate:
  image: ${LUMORA_IMAGE}
  environment:
    LUMORA_MODE: migrate
    MIGRATION_MYSQL_USERNAME: ${MIGRATION_MYSQL_USERNAME}
    MIGRATION_MYSQL_PASSWORD: ${MIGRATION_MYSQL_PASSWORD}
```

`deploy/compatibility-smoke.sh <current-image> <candidate-image>` runs each immutable runtime image with `LUMORA_MODE=schema-smoke` against a disposable clone restored from the expanded-schema backup. It does not try to execute test classes from production images.

### Ingestion Time and Deduplication Contract

`WechatEventIngestionService` receives an injected `Clock`. Let `receivedAt=clock.instant()`, `originalOccurredAt=Instant.ofEpochSecond(CreateTime)`, and anomaly be:

```java
boolean anomalous = originalOccurredAt.isBefore(receivedAt.minus(30, DAYS))
    || originalOccurredAt.isAfter(receivedAt.plus(30, DAYS));
Instant effectiveOccurredAt = anomalous ? receivedAt : originalOccurredAt;
```

Exactly ±30 days is not anomalous; one microsecond beyond is anomalous. Persist all three values and increment Micrometer counter `lumora.wechat.timestamp.anomalous`.

Length encoding is four unsigned big-endian bytes followed by UTF-8 bytes. Null is encoded as `0xffffffff`; empty string is length zero. Decimal coordinates use `stripTrailingZeros().toPlainString()`. Field order is exactly the nine-item list in the spec. Composite canonicalization sorts XML field names lexicographically, recursively length-encodes names/values, hashes full content, and exposes only type, item count, and hash to the outer fingerprint. Tests assert literal `msgid:12345`; SHA tests compute expected output independently with `MessageDigest`, rather than copying production helpers.

### Callback Status Matrix

`WechatCallbackControllerTest` is parameterized over this exact matrix and verifies `repository.insert` has zero interactions for every rejected row:

| Mode | Condition | Status/body |
| --- | --- | --- |
| GET | route AppID mismatch | 404 |
| GET | invalid signature | 403 |
| GET | valid | 200, exact `echostr`, `text/plain` |
| plaintext POST | route AppID mismatch | 404 |
| plaintext POST | invalid signature | 403 |
| plaintext POST | original ID mismatch | 403 |
| encrypted POST | route AppID mismatch | 404 |
| encrypted POST | invalid `msg_signature` | 403 |
| encrypted POST | envelope AppID mismatch | 403 |
| encrypted POST | decrypted original ID mismatch | 403 |
| either POST | malformed/XXE XML | 400 |
| either POST | body over 262144 bytes | 413 |
| either POST | non-event message | 200, exact `success`, zero inserts |
| either POST | inserted or duplicate | 200, exact `success` |
| either POST | database failure | 503 |

Fixtures live in `src/test/resources/wechat/` as `subscribe.xml`, `qr-subscribe.xml`, `scan.xml`, `location.xml`, `click.xml`, `view.xml`, six `menu-other-*.xml`, `unknown-event.xml`, `text-message.xml`, and encrypted equivalents generated in test setup from a fixed non-production key.

### Report Assertion Matrix

`DailyReportServiceTest` inserts a fixed fixture dataset through the repository, then asserts exact event-count and distinct-user-count pairs for every internal event type. It must separately assert:

- QR subscribe contributes to both `SUBSCRIBE` and its scene row.
- `SCAN` with the same scene shares that scene row.
- `MENU_CLICK` and `MENU_VIEW` remain separate and sum to combined menu total.
- `MENU_OTHER` groups by both raw Event and EventKey.
- Scene/key/URL null and blank normalize to exactly `（未提供）`.
- Location summary has counts but rendered HTML/text contain neither decimal coordinate.
- `UNKNOWN` contributes to totals and unique users.
- anomalous timestamp count is present.
- rows received after `data_cutoff_at` are absent from immutable v1.
- AUTO creates or reuses only version 1.
- only `regenerate=true` creates N+1.

MySQL integration paths are `src/test/java/cn/minglli/lumora/report/DailyReportMapperTest.java` and `ReportDeliveryMapperConcurrencyTest.java`. Run:

```bash
mvn -q -Dtest=DailyReportMapperTest,ReportDeliveryMapperConcurrencyTest test
```

### Delivery SQL and State Contract

Legal transitions are:

```text
PENDING -> SENDING
SENDING -> SENT
SENDING -> PENDING  (transient failure with attempts remaining)
SENDING -> FAILED   (permanent failure or attempts exhausted)
SENDING -> SENDING  (expired lease reclaimed; attempt_count increments)
```

No transition out of `SENT` or `FAILED` is allowed; force creates a new row. The claimant performs:

```sql
UPDATE report_delivery_attempt
SET status='SENDING', claimed_at=:now, lease_until=:leaseUntil,
    attempt_count=attempt_count+1
WHERE id=:id
  AND (
    status='PENDING'
    OR (status='SENDING' AND lease_until < :now)
  );
```

AUTO creation uses `INSERT ... ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id)` inside the same transaction, followed by claim. Manual creation uses stable UUID `delivery_id`, stores recipient masks such as `m***@qq.com` plus SHA-256 of sorted normalized addresses, and reads full recipients only from protected in-memory configuration immediately before claim/send. Repeating the same `X-Request-Id` returns the existing delivery ID and state without sending again.

### Candidate, Retention, and Operations Contract

Retention runs at `0 30 3 * * *` in `Asia/Shanghai` only when `RETENTION_ENABLED=true`. In one transaction it:

1. Nulls coordinate columns where `received_at < now-30d`.
2. Deletes delivery rows whose own `created_at < now-400d`.
3. Deletes reports with `created_at < now-400d` only when no retained delivery row references them.
4. Deletes events with `received_at < now-400d`.

Exact cutoff values are retained; only strictly older rows mutate. `CandidateModeTest` at `src/test/java/cn/minglli/lumora/operations/CandidateModeTest.java` starts with all four flags false and asserts no `ScheduledAnnotationBeanPostProcessor` task for daily/recovery/retention, POST internal send returns 503, and advancing a mutable clock causes zero database changes.

The application makes liveness and readiness available on its internal listener, but public Nginx exposes only `/actuator/health/liveness`. Public `/actuator/health/readiness`, `/internal/**`, and all other Actuator paths return 404. `OperationsEndpointTest` checks application-side sensitive endpoint protection; `deploy/tests/deploy_contract_test.sh` checks the public ingress allowlist. Unknown events increment `lumora.wechat.event.unknown`. Log tests capture output and assert no full OpenID, coordinate, recipient, auth code, token, or AES key appears.

### Packaging and Deployment Test Paths

Create and run:

- `src/test/java/cn/minglli/lumora/ApplicationContextTest.java`: production-shaped context with no-op `MailGateway`.
- `src/test/java/cn/minglli/lumora/e2e/CallbackToReportE2eTest.java`: Testcontainers MySQL; POST fixture twice; assert one row; generate snapshot; assert exact count.
- `src/test/java/cn/minglli/lumora/operations/ActuatorHealthTest.java`: liveness/readiness response checks.
- `deploy/tests/deploy_contract_test.sh`: fake `docker`, `curl`, and proxy commands on `PATH`; verifies candidate flags, migration order, health-before-switch, signed public GET and idempotent POST before worker cutover, public liveness-only ingress, worker ordering, and rollback.
- `deploy/verify-packaging.sh`: runs `docker compose config`, `bash -n`, image non-root inspection, and scans image history/config for secret-shaped values.

Task 8 runs after building `lumora:test`:

```bash
mvn -q -Dtest=ApplicationContextTest,CallbackToReportE2eTest,ActuatorHealthTest test
bash deploy/verify-packaging.sh lumora:test
```

Public web, operations, and background work run as separate containers from the same image:

- `lumora-web-{blue,green}` is the public callback container. All four background/internal flags are false.
- `lumora-ops-<version>` binds only to `127.0.0.1`/the private Compose network. Daily/recovery/retention flags are false and `INTERNAL_SEND_ENABLED=true`; Nginx has no public route to it.
- Exactly one `lumora-worker-<version>` runs non-web with daily/recovery/retention enabled and internal send disabled.

Worker startup executes `WorkerReadinessVerifier`: it confirms worker mode, a successful `SELECT 1`, and registration of the daily, recovery, and retention scheduled tasks, then atomically creates `/tmp/lumora-worker-ready`. Its Docker health check is:

```yaml
healthcheck:
  test: ["CMD-SHELL", "test -f /tmp/lumora-worker-ready"]
  interval: 5s
  timeout: 2s
  retries: 12
```

The marker is inside the container and disappears on replacement. `WorkerReadinessVerifierTest` uses a temporary marker path, missing-database and missing-scheduler fixtures, and asserts the marker is created only after all checks pass.

The release order is:

1. Run expand-only migrator.
2. Run current and candidate `schema-smoke` against a disposable expanded-schema clone.
3. Start inactive candidate web and pass local health.
4. Switch proxy to candidate web.
5. Pass signed public GET and signed idempotent POST persistence verification.
6. Start the candidate-version operations container on a private/local address, verify admin authentication locally, and stop the old operations container.
7. Stop the old worker and confirm it released/stopped background work.
8. Start the candidate-version worker with enabled background flags and wait until its Docker health status is `healthy`.
9. Mark the release successful and retain old web/image.

At most one preferred worker exists because the new worker is not started until the old worker stops; database leases remain the final safeguard. If a failure occurs through step 5, proxy switches to old web and old worker remains untouched. If operations replacement fails, restart/retain old operations and switch proxy back. If worker health fails, switch proxy to old web, stop the unhealthy worker, and restart the old-version worker. No dynamic mutation of startup-bound flags is attempted.

`README.md` must document a least-privilege application database user (DML only after migration), separate migration user (DDL), encrypted database volume/backup requirements, 30-day encrypted backup retention, restoration drill, and prohibition on copying production backups to personal/non-controlled systems.

Final security verification additionally runs:

```bash
git grep -n -I -E '(sk-[A-Za-z0-9_-]{20,}|[A-Za-z0-9_-]{43}|(password|auth.?code|secret|token)[=:][^$<{[:space:]]{8,})' -- ':!docs/superpowers/**'
docker history --no-trunc lumora:verify
docker image inspect lumora:verify
```

Review each match; expected result is placeholders only and no secret in image layers, environment, labels, or commands.
