# WeChat Server API Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the authenticated outbound WeChat API client, safe access-token lifecycle, shared error handling, five basic APIs, and the two read-only OpenAPI diagnostics on which all later API modules depend.

**Architecture:** Keep outbound calls under a new `wechat.api` package, separate from the existing inbound callback protocol. A conditionally enabled configuration creates a Spring `RestClient`; an in-process single-flight token provider caches stable access tokens with an early-refresh skew, while the stable-token API prevents rotating the token when another Lumora role refreshes independently. Feature services use one transport that maps WeChat error envelopes, captures `rid`, redacts secrets, and retries a request only when the operation explicitly declares that replay is safe.

**Tech Stack:** Java 17, Spring Boot 3.3 `RestClient`, Jackson, Jakarta Validation, JUnit 5, AssertJ, Mockito, Spring `MockRestServiceServer`

**Scope:** This is plan 1 of the approved 43-interface roadmap. It implements the five “基础接口” entries plus “查询 API 调用额度” and “查询 rid 信息”. The three destructive quota-reset APIs are intentionally reserved for a later operations-only plan.

---

## File map

### Configuration

- Modify `backend/src/main/java/cn/minglli/lumora/config/LumoraProperties.java`
  - Add separate outbound-client and internal-diagnostics switches, AppSecret, base URL, timeouts, and token refresh skew.
- Modify `backend/src/main/resources/application.yml`
  - Bind `WECHAT_API_*` and `WECHAT_APP_SECRET`.
- Modify `backend/.env.example`
  - Document production configuration without adding a real secret.
- Modify `backend/.env.dev.example`
  - Keep outbound API disabled for callback-only local development.
- Modify `backend/src/test/java/cn/minglli/lumora/config/LumoraPropertiesTest.java`
  - Cover defaults and conditional outbound configuration.
- Modify `backend/src/test/java/cn/minglli/lumora/config/LumoraApplicationConfigurationTest.java`
  - Verify enabled startup requires an AppSecret.

### Shared outbound client

- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiConfiguration.java`
  - Build the conditionally enabled `RestClient`.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiException.java`
  - Represent WeChat `errcode`, `errmsg`, optional `rid`, endpoint name, and retryability without storing secrets.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiEnvelope.java`
  - Parse common error fields from successful HTTP responses.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiTransport.java`
  - Execute JSON requests, attach access tokens, map errors, and retry only replay-safe operations once after token invalidation.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatAccessTokenProvider.java`
  - Define `currentToken()` and `invalidate(token)` before the transport is compiled.
- Create `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatApiTransportTest.java`
  - Test success, WeChat errors, malformed responses, redaction, and token-expiry retry policy.

### Credentials

- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatAccessToken.java`
  - Hold token text and absolute expiry internally.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/StableWechatAccessTokenProvider.java`
  - Fetch `/cgi-bin/stable_token`, cache with early refresh, and single-flight concurrent refreshes.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/LegacyWechatAccessTokenClient.java`
  - Implement the authorized legacy `/cgi-bin/token` API as an explicit diagnostic method; do not use it as the application token source.
- Create `backend/src/test/java/cn/minglli/lumora/wechat/api/StableWechatAccessTokenProviderTest.java`
  - Test cache, expiry, refresh, concurrency, failures, and absence of secret logging.
- Create `backend/src/test/java/cn/minglli/lumora/wechat/api/LegacyWechatAccessTokenClientTest.java`
  - Test response parsing and secret-safe errors.

### Basic and OpenAPI diagnostics

- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatNetworkDiagnosticsClient.java`
  - Implement network communication detection and both server-IP APIs.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatOpenApiDiagnosticsClient.java`
  - Implement quota and rid queries.
- Create `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatDiagnosticsController.java`
  - Expose safe results below `/internal/wechat/diagnostics/**`; never return an access token or AppSecret.
- Create `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatNetworkDiagnosticsClientTest.java`
- Create `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatOpenApiDiagnosticsClientTest.java`
- Create `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatDiagnosticsControllerTest.java`
- Modify `backend/src/test/java/cn/minglli/lumora/operations/AdminKeyInterceptorTest.java`
  - Prove all new internal routes remain protected.
- Modify `backend/compose.yaml`
  - Keep diagnostics disabled for local callback roles.
- Modify `deploy/k8s/lumora-backend.yaml`
  - Enable diagnostics only on the private `ops` role and explicitly disable them on `web` and `worker`.
- Modify `deploy/k8s/lumora-backend-migrate.yaml`
  - Explicitly disable outbound calls and diagnostics for migration/schema-smoke jobs.
- Modify `deploy/deploy-backend.sh`
  - Pass and validate the role-specific switches during rollout.
- Modify `backend/deploy/check-env.sh`
  - Require a nonblank AppSecret before a deployment that enables the ops diagnostics role.
- Modify `deploy/tests/deploy_contract_test.sh`
  - Enforce the role-specific switch contract.

### Documentation

- Modify `backend/README.md`
  - Document the outbound feature switch, secret handling, and diagnostic routes.
- Modify `docs/superpowers/specs/2026-07-31-wechat-api-implementation-inventory-design.md`
  - Mark the seven completed interfaces only after the full verification task passes.

## API contracts locked by this plan

Use the current official WeChat documentation as the source of truth during implementation. Before writing each endpoint test, verify its HTTP method, path, required fields, response fields, and error semantics. Do not infer contracts from blog posts.

The application-facing interfaces should have these shapes:

```java
public interface WechatAccessTokenProvider {
    String currentToken();
    void invalidate(String rejectedToken);
}
```

```java
public record WechatQuota(String cgiPath, long dailyLimit, long used, long remaining) {}
record WechatRidWireResponse(int errcode, String errmsg, WechatRidWireRequest request) {}
record WechatRidWireRequest(
        long invokeTime,
        int costInMs,
        String requestUrl,
        String requestBody,
        String responseBody,
        String clientIp) {}
public record SafeWechatRidDetails(
        String rid,
        long invokeTime,
        int costInMs,
        String clientIp) {}
public record WechatServerIps(List<String> addresses) {}
public record WechatNetworkCheckResult(List<WechatNetworkCheckItem> dns, List<WechatNetworkCheckItem> ping) {}
```

`WechatApiTransport` must require callers to declare replay safety:

```java
public enum ReplayPolicy {
    NEVER,
    ONCE_AFTER_TOKEN_REFRESH
}
```

Only read-only endpoints in this plan use `ONCE_AFTER_TOKEN_REFRESH`. Future create, update, upload, delete, or reset calls default to `NEVER` until their idempotency behavior is explicitly designed.

### Task 1: Add opt-in outbound API configuration

**Files:**
- Modify: `backend/src/main/java/cn/minglli/lumora/config/LumoraProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/.env.example`
- Modify: `backend/.env.dev.example`
- Test: `backend/src/test/java/cn/minglli/lumora/config/LumoraPropertiesTest.java`
- Test: `backend/src/test/java/cn/minglli/lumora/config/LumoraApplicationConfigurationTest.java`

- [ ] **Step 1: Write failing property-binding tests**

Add assertions for these defaults:

```java
assertThat(properties.isWechatApiEnabled()).isFalse();
assertThat(properties.isWechatDiagnosticsEnabled()).isFalse();
assertThat(properties.getWechatApiBaseUrl()).isEqualTo("https://api.weixin.qq.com");
assertThat(properties.getWechatApiConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
assertThat(properties.getWechatApiReadTimeout()).isEqualTo(Duration.ofSeconds(15));
assertThat(properties.getWechatTokenRefreshSkew()).isEqualTo(Duration.ofMinutes(5));
```

Add an application-context test proving `WECHAT_API_ENABLED=true` with blank `WECHAT_APP_SECRET` fails startup with a message containing `WECHAT_APP_SECRET`.
Also prove `WECHAT_DIAGNOSTICS_ENABLED=true` while `WECHAT_API_ENABLED=false` fails startup, and that both switches disabled start without an AppSecret.

- [ ] **Step 2: Run the focused tests and confirm they fail**

Run:

```bash
cd backend
mvn -Dtest=LumoraPropertiesTest,LumoraApplicationConfigurationTest test
```

Expected: FAIL because the outbound properties and validation do not exist.

- [ ] **Step 3: Add the minimal properties and conditional validation**

Add properties with these environment mappings:

```yaml
lumora:
  wechat-api-enabled: ${WECHAT_API_ENABLED:false}
  wechat-diagnostics-enabled: ${WECHAT_DIAGNOSTICS_ENABLED:false}
  wechat-app-secret: ${WECHAT_APP_SECRET:}
  wechat-api-base-url: ${WECHAT_API_BASE_URL:https://api.weixin.qq.com}
  wechat-api-connect-timeout: ${WECHAT_API_CONNECT_TIMEOUT:5s}
  wechat-api-read-timeout: ${WECHAT_API_READ_TIMEOUT:15s}
  wechat-token-refresh-skew: ${WECHAT_TOKEN_REFRESH_SKEW:5m}
```

Use an `@AssertTrue` method on `LumoraProperties` so AppSecret is mandatory only when outbound calls are enabled:

```java
@AssertTrue(message = "WECHAT_APP_SECRET must not be blank when WECHAT_API_ENABLED=true")
public boolean isWechatApiSecretConfigurationValid() {
    return !wechatApiEnabled || (wechatAppSecret != null && !wechatAppSecret.isBlank());
}
```

Add a second invariant: diagnostics may be enabled only when the outbound client is enabled.

Keep `WECHAT_API_ENABLED=false` in `.env.dev.example`. Add placeholders only; never add a real AppSecret.

- [ ] **Step 4: Run the focused tests**

Run: `mvn -Dtest=LumoraPropertiesTest,LumoraApplicationConfigurationTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/config/LumoraProperties.java backend/src/main/resources/application.yml backend/.env.example backend/.env.dev.example backend/src/test/java/cn/minglli/lumora/config/LumoraPropertiesTest.java backend/src/test/java/cn/minglli/lumora/config/LumoraApplicationConfigurationTest.java
git commit -m "feat(backend): configure outbound WeChat API"
```

### Task 2: Build the secret-safe transport and error model

**Files:**
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiConfiguration.java`
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiEnvelope.java`
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiException.java`
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatApiTransport.java`
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatAccessTokenProvider.java`
- Test: `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatApiTransportTest.java`

- [ ] **Step 1: Write failing transport tests**

Cover:

```java
@Test void parsesSuccessfulJsonIntoTheRequestedType() {}
@Test void mapsHttp200WechatErrorEnvelopeToTypedException() {}
@Test void preservesRidForDiagnostics() {}
@Test void sanitizesAndBoundsResponseRidBeforeExceptionAndDiagnosticLog() {}
@Test void responseRidDiagnosticLogContainsNoTokenBodyOrCrLfCanaries() {}
@Test void mapsNon2xxAndMalformedJsonWithoutIncludingBodyOrQuerySecrets() {}
@Test void wrapsConnectionRefusalWithoutLeakingAccessTokenFromUri() {}
@Test void wrapsTimeoutWithoutLeakingAccessTokenFromUri() {}
@Test void doesNotRetryWhenReplayPolicyIsNever() {}
@ParameterizedTest void refreshesOnceForReadOnlyCallRejectedWithTokenCode(int errcode) {}
@Test void doesNotRefreshForOtherBusinessErrors() {}
@Test void returnsSanitizedSecondFailureAfterTokenRefresh() {}
```

Use `MockRestServiceServer.bindTo(restClientBuilder).build()`; test URLs and bodies locally without network access.

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -Dtest=WechatApiTransportTest test`

Expected: FAIL because the transport classes do not exist.

- [ ] **Step 3: Implement configuration and common envelopes**

Create the client only when `lumora.wechat-api-enabled=true`. Configure connect/read timeouts from properties and a base URL, but do not add a logging interceptor that can see bodies or query strings.

`WechatApiException#getMessage()` may include only:

```text
WeChat API <operation> failed: errcode=<code>, rid=<rid-or-absent>
```

Never include request JSON, response JSON, token, AppSecret, full URI, or user-supplied content.
Catch and wrap every `RestClientException`, including `ResourceAccessException` from connection refusal and timeout; never propagate Spring's original message because it may contain the full authenticated URI. Do not retain the original throwable as a cause. If diagnostics need its type, retain only a bounded allowlisted category such as `CONNECT`, `TIMEOUT`, or `HTTP`, never `Throwable#getMessage()`. Add a test that logs the complete wrapper stack trace and proves token/AppSecret canaries are absent.

At the common transport boundary, normalize every `rid` returned in a WeChat error envelope before it reaches an exception, controller, or logger: cap its length, allow only a conservative printable identifier character set, and replace CR/LF/control characters. Store only that sanitized value in `WechatApiException`. Emit one structured diagnostic containing only the operation name, numeric errcode, and sanitized rid; never log `errmsg`, request/response bodies, URI, token, or exception cause. Cover this common log path with rid/token/body/CRLF canaries so every later WeChat client inherits the guarantee.

- [ ] **Step 4: Implement explicit replay policy**

For authenticated requests:

1. Obtain a token.
2. Execute once.
3. If WeChat reports `40014` (invalid access token) or `42001` (expired access token) and policy is `ONCE_AFTER_TOKEN_REFRESH`, invalidate that exact token and retry once.
4. All other errors return immediately.

Do not retry HTTP timeouts, connection failures, malformed responses, non-token business errors, or ambiguous transport failures in this layer. Tests must cover both allowlisted codes, an unrelated errcode, `ReplayPolicy.NEVER`, and failure of the second attempt.

- [ ] **Step 5: Run the test**

Run: `mvn -Dtest=WechatApiTransportTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/wechat/api backend/src/test/java/cn/minglli/lumora/wechat/api/WechatApiTransportTest.java
git commit -m "feat(backend): add WeChat API transport"
```

### Task 3: Implement stable access-token caching

**Files:**
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatAccessToken.java`
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/StableWechatAccessTokenProvider.java`
- Test: `backend/src/test/java/cn/minglli/lumora/wechat/api/StableWechatAccessTokenProviderTest.java`
- Modify: `backend/src/test/java/cn/minglli/lumora/operations/LogRedactionTest.java`

- [ ] **Step 1: Write failing token-provider tests**

Cover:

```java
@Test void returnsCachedTokenBeforeRefreshBoundary() {}
@Test void refreshesAtExpiresAtMinusConfiguredSkew() {}
@Test void concurrentCallersShareOneRefreshRequest() {}
@Test void invalidatingCurrentTokenForcesRefresh() {}
@Test void invalidatingOldTokenDoesNotDiscardNewToken() {}
@Test void failedRefreshDoesNotCacheAnErrorOrEmptyToken() {}
@Test void mapsWechatBusinessErrorWithoutLeakingSecretOrToken() {}
@Test void rejectsNonPositiveExpiresIn() {}
@Test void mapsMalformedSuccessResponseToSanitizedException() {}
@Test void wrapsConnectionFailureWithoutLeakingAppSecretOrFullUri() {}
@Test void wrapsTimeoutWithoutLeakingAppSecretOrFullUri() {}
@Test void logsNeverContainAppSecretOrAccessToken() {}
```

Use a fixed mutable test clock and an executor with a barrier for the concurrency case.

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -Dtest=StableWechatAccessTokenProviderTest,LogRedactionTest test`

Expected: FAIL because the provider does not exist.

- [ ] **Step 3: Implement stable-token acquisition**

Send the documented JSON request to `/cgi-bin/stable_token`:

```json
{
  "grant_type": "client_credential",
  "appid": "<configured app id>",
  "secret": "<configured app secret>",
  "force_refresh": false
}
```

Keep the request DTO private to the token provider. Store the secret only in configuration and the outbound request object; never retain it in exceptions.

- [ ] **Step 4: Implement cache and single-flight refresh**

Use a small synchronized critical section with double-checking. Set:

```java
expiresAt = clock.instant().plusSeconds(expiresIn);
refreshAt = expiresAt.minus(refreshSkew);
```

If the configured skew is longer than the returned lifetime, refresh no earlier than halfway through the lifetime. The stable-token endpoint is deliberately used so separate Lumora roles do not rotate each other’s token; in-process locking prevents a local refresh stampede.

- [ ] **Step 5: Run the tests**

Run: `mvn -Dtest=StableWechatAccessTokenProviderTest,LogRedactionTest test`

Expected: PASS and no secret appears in captured logs.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/wechat/api backend/src/test/java/cn/minglli/lumora/wechat/api/StableWechatAccessTokenProviderTest.java backend/src/test/java/cn/minglli/lumora/operations/LogRedactionTest.java
git commit -m "feat(backend): cache stable WeChat access token"
```

### Task 4: Implement the legacy credential API without using it for authentication

**Files:**
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/LegacyWechatAccessTokenClient.java`
- Test: `backend/src/test/java/cn/minglli/lumora/wechat/api/LegacyWechatAccessTokenClientTest.java`

- [ ] **Step 1: Write failing contract tests**

Verify the exact documented HTTP method, path, query fields, success response, error envelope, and absence of query secrets from exception messages.
Also test a WeChat business error, malformed success response, connection refusal, and timeout with AppSecret/token canaries.

- [ ] **Step 2: Run the focused test**

Run: `mvn -Dtest=LegacyWechatAccessTokenClientTest test`

Expected: FAIL because the client does not exist.

- [ ] **Step 3: Implement the minimal client**

Expose a package-private diagnostic method returning token metadata to trusted internal code. Do not expose it through a controller, do not cache its result, and do not wire it as `WechatAccessTokenProvider`.

- [ ] **Step 4: Run the focused test**

Run: `mvn -Dtest=LegacyWechatAccessTokenClientTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/wechat/api/LegacyWechatAccessTokenClient.java backend/src/test/java/cn/minglli/lumora/wechat/api/LegacyWechatAccessTokenClientTest.java
git commit -m "feat(backend): add legacy WeChat token diagnostic"
```

### Task 5: Implement the three network and server-IP diagnostics

**Files:**
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatNetworkDiagnosticsClient.java`
- Test: `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatNetworkDiagnosticsClientTest.java`

- [ ] **Step 1: Write failing contract tests**

Cover the authorized APIs:

```java
@Test void checksNetworkCommunicationForSelectedActionAndOperator() {}
@Test void getsWechatApiServerIps() {}
@Test void getsWechatCallbackServerIps() {}
@Test void rejectsUnknownActionBeforeSendingRequest() {}
@ParameterizedTest void eachOperationMapsWechatBusinessError(String operation) {}
@ParameterizedTest void eachOperationWrapsNetworkErrorWithoutTokenLeak(String operation) {}
@ParameterizedTest void eachOperationRejectsMalformedResponse(String operation) {}
```

Model action and operator as enums using only official documented values, not arbitrary strings.

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -Dtest=WechatNetworkDiagnosticsClientTest test`

Expected: FAIL because the client does not exist.

- [ ] **Step 3: Implement the client**

Use the shared authenticated transport and `ONCE_AFTER_TOKEN_REFRESH`. Return immutable lists. Reject missing or malformed IP strings instead of passing them into logs.

- [ ] **Step 4: Run the test**

Run: `mvn -Dtest=WechatNetworkDiagnosticsClientTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/wechat/api/WechatNetworkDiagnosticsClient.java backend/src/test/java/cn/minglli/lumora/wechat/api/WechatNetworkDiagnosticsClientTest.java
git commit -m "feat(backend): add WeChat network diagnostics"
```

### Task 6: Implement quota and rid queries

**Files:**
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatOpenApiDiagnosticsClient.java`
- Test: `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatOpenApiDiagnosticsClientTest.java`
- Modify: `backend/src/test/java/cn/minglli/lumora/operations/LogRedactionTest.java`

- [ ] **Step 1: Write failing contract and validation tests**

Cover:

```java
@Test void queriesQuotaForCanonicalCgiPath() {}
@Test void queriesRequestDetailsByRid() {}
@Test void rejectsBlankOrNonCgiPathBeforeRequest() {}
@Test void rejectsBlankRidBeforeRequest() {}
@Test void mapsMissingOptionalRidFieldsWithoutFailure() {}
@ParameterizedTest void eachOperationMapsWechatBusinessError(String operation) {}
@ParameterizedTest void eachOperationWrapsNetworkErrorWithoutTokenLeak(String operation) {}
@ParameterizedTest void eachOperationRejectsMalformedResponse(String operation) {}
@Test void neverExposesRidRequestUrlEvenWhenItContainsOnlyTokenQuery() {}
@Test void boundsRidFieldsAndNeutralizesCrLf() {}
@Test void loggedRidDiagnosticContainsNoTokenBodyOrControlCharacters() {}
```

The quota input is a canonical CGI path such as `/cgi-bin/material/batchget_material`, never a full URL and never a user-controlled host.

- [ ] **Step 2: Run the focused test**

Run: `mvn -Dtest=WechatOpenApiDiagnosticsClientTest test`

Expected: FAIL because the client does not exist.

- [ ] **Step 3: Implement both read-only operations**

Use separate records for the official wire response and the sanitized controller response. Parse timestamps into `Instant` only if the official response contract defines the unit unambiguously; otherwise retain the documented wire type and convert at the controller boundary with a named formatter.

For RID details, model the official nested `request` object containing `invoke_time`, `cost_in_ms`, `request_url`, `request_body`, `response_body`, and `client_ip`. The safe application/controller DTO exposes only the caller-supplied bounded rid, invoke time, cost, and a validated client IP:

- never expose `request_url`; WeChat may return only a secret-bearing query string rather than a parseable absolute URL;
- never expose or log `request_body` or `response_body`;
- bound the rid and neutralize CR, LF, and other control characters before returning or logging;
- validate `client_ip` as an IP address and omit it if invalid;
- add a query-only `access_token=TOKEN_CANARY` wire-response test;
- add a sanitized INFO diagnostic containing only the bounded rid and operation, with token/body/CRLF canary assertions in `LogRedactionTest`.

- [ ] **Step 4: Run the focused test**

Run: `mvn -Dtest=WechatOpenApiDiagnosticsClientTest,LogRedactionTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/wechat/api/WechatOpenApiDiagnosticsClient.java backend/src/test/java/cn/minglli/lumora/wechat/api/WechatOpenApiDiagnosticsClientTest.java backend/src/test/java/cn/minglli/lumora/operations/LogRedactionTest.java
git commit -m "feat(backend): query WeChat quota and rid"
```

### Task 7: Add protected internal diagnostic endpoints

**Files:**
- Create: `backend/src/main/java/cn/minglli/lumora/wechat/api/WechatDiagnosticsController.java`
- Test: `backend/src/test/java/cn/minglli/lumora/wechat/api/WechatDiagnosticsControllerTest.java`
- Modify: `backend/src/test/java/cn/minglli/lumora/operations/AdminKeyInterceptorTest.java`
- Modify: `backend/compose.yaml`
- Modify: `deploy/k8s/lumora-backend.yaml`
- Modify: `deploy/k8s/lumora-backend-migrate.yaml`
- Modify: `deploy/deploy-backend.sh`
- Modify: `backend/deploy/check-env.sh`
- Modify: `deploy/tests/deploy_contract_test.sh`

- [ ] **Step 1: Write failing MVC tests**

Add protected endpoints:

```text
POST /internal/wechat/diagnostics/network-check
GET  /internal/wechat/diagnostics/api-server-ips
GET  /internal/wechat/diagnostics/callback-server-ips
GET  /internal/wechat/diagnostics/quota?cgiPath=...
GET  /internal/wechat/diagnostics/rids/{rid}
```

Tests must prove:

- no/malformed `X-Lumora-Admin-Key` is rejected;
- valid admin authentication reaches the service;
- validation failures return 400;
- WeChat errors return a sanitized 502 containing errcode and rid only;
- no endpoint returns an access token or AppSecret.
- the controller bean is absent when `WECHAT_DIAGNOSTICS_ENABLED=false`;
- a context with diagnostics disabled starts without an AppSecret.

- [ ] **Step 2: Run the MVC tests**

Run: `mvn -Dtest=WechatDiagnosticsControllerTest,AdminKeyInterceptorTest test`

Expected: FAIL because the controller does not exist.

- [ ] **Step 3: Implement the controller and sanitized exception mapping**

Annotate the controller with `@ConditionalOnProperty(name = "lumora.wechat-diagnostics-enabled", havingValue = "true")`. Reuse the existing `/internal/**` interceptor. Keep DTOs immutable. Do not add an endpoint for either token API.

Update role configuration:

- `web`: `WECHAT_API_ENABLED=false`, `WECHAT_DIAGNOSTICS_ENABLED=false`;
- `worker`: `WECHAT_API_ENABLED=false`, `WECHAT_DIAGNOSTICS_ENABLED=false` until a later business module needs outbound calls;
- `ops`: `WECHAT_API_ENABLED=true`, `WECHAT_DIAGNOSTICS_ENABLED=true`;
- Compose callback services: both false;
- migrate/schema-smoke: both false.

Extend `deploy/tests/deploy_contract_test.sh` to parse/assert these exact role values for Deployments and Jobs and to prove no public Ingress routes `/internal/**`. Update `deploy/deploy-backend.sh` and `backend/deploy/check-env.sh` so a rollout fails before changing cluster state when the ops role will be enabled but `WECHAT_APP_SECRET` is blank. Keep the secret in the generated Kubernetes Secret; never render it into a manifest or command output.

- [ ] **Step 4: Run the MVC tests**

Run: `mvn -Dtest=WechatDiagnosticsControllerTest,AdminKeyInterceptorTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/cn/minglli/lumora/wechat/api/WechatDiagnosticsController.java backend/src/test/java/cn/minglli/lumora/wechat/api/WechatDiagnosticsControllerTest.java backend/src/test/java/cn/minglli/lumora/operations/AdminKeyInterceptorTest.java backend/compose.yaml deploy/k8s/lumora-backend.yaml deploy/k8s/lumora-backend-migrate.yaml deploy/deploy-backend.sh backend/deploy/check-env.sh deploy/tests/deploy_contract_test.sh
git commit -m "feat(backend): expose protected WeChat diagnostics"
```

### Task 8: Document, verify, and update the inventory

**Files:**
- Modify: `backend/README.md`
- Modify: `docs/superpowers/specs/2026-07-31-wechat-api-implementation-inventory-design.md`

- [ ] **Step 1: Document configuration and operations**

Document:

- `WECHAT_API_ENABLED` remains false until `WECHAT_APP_SECRET` is provisioned;
- AppSecret belongs in the deployment secret and must not enter Git;
- only the `ops` role should expose internal diagnostics through its ClusterIP;
- example authenticated requests use placeholders;
- access-token values are never returned.

- [ ] **Step 2: Run all unit tests**

Run:

```bash
cd backend
mvn test
```

Expected: BUILD SUCCESS.

- [ ] **Step 3: Run packaging verification**

Run:

```bash
cd backend
docker build -t lumora:wechat-api-foundation .
bash deploy/verify-packaging.sh lumora:wechat-api-foundation
```

Expected: Docker build succeeds and packaging verification reports success for `lumora:wechat-api-foundation`.

- [ ] **Step 4: Run deployment contract tests**

Run from the repository root:

```bash
bash deploy/tests/deploy_contract_test.sh
```

Expected: PASS, including diagnostics enabled only for `ops` and no public internal route.

- [ ] **Step 5: Inspect tracked changes for secret leaks**

Run from the repository root:

```bash
git diff --check
git diff --cached --check
git grep -nE 'WECHAT_APP_SECRET=.+|access_token["=: ]+[A-Za-z0-9_-]{16,}' -- ':!docs/superpowers/plans/**'
```

Expected: diff checks pass; grep finds no committed real secret or token.

- [ ] **Step 6: Mark exactly seven inventory entries complete**

Mark complete only:

- 获取接口调用凭据
- 获取稳定版接口调用凭据
- 网络通信检测
- 获取微信 API 服务器 IP
- 获取微信推送服务器 IP
- 查询 API 调用额度
- 查询 rid 信息

Leave all reset operations and all other modules unchecked.

- [ ] **Step 7: Commit documentation and inventory status**

```bash
git add backend/README.md docs/superpowers/specs/2026-07-31-wechat-api-implementation-inventory-design.md
git commit -m "docs: document WeChat API foundation"
```

## Follow-on plans

After this plan passes, write and review separate implementation plans in this order:

1. `wechat-readonly-metadata`: custom menu, automatic reply, material counts/lists/details, draft queries, product card DOM, store categories/regions/maps/details/lists, and JS-SDK ticket.
2. `wechat-material-lifecycle`: temporary and permanent material upload/download/delete.
3. `wechat-draft-content`: article image upload and draft create/update/delete.
4. `wechat-ai`: translation and asynchronous speech-recognition upload/result polling.
5. `wechat-store-lifecycle`: store mini-program creation/update/review plus store create/delete.
6. `wechat-quota-operations`: three destructive reset APIs behind operations-only confirmation, audit, and rate limiting.

Each follow-on plan must re-check current official endpoint documentation before locking request and response contracts.
