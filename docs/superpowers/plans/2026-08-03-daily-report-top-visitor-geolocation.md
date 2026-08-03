# Daily Report Top Visitor Geolocation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add successful-page Top 3 visitor IPs and resilient city/ISP geolocation to the dev1 access email report while preserving existing metrics.

**Architecture:** Keep `summarize()` pure by aggregating Top 3 visitors from the existing `pages` list, then explicitly enrich those rows in `cmd_report()` through an injected online provider. The provider owns IP validation, `ipinfo.is` response normalization, and an atomic 30-day JSON cache; `render()` consumes only normalized rows and never performs I/O. A future MaxMind provider can implement the same `lookup(ip)` contract.

**Tech Stack:** Python 3.6 standard library (`unittest`, `ipaddress`, `urllib.request`, `json`, `tempfile`/atomic `os.replace`), existing HTML email renderer, shell deployment scripts.

---

## File map

- Create `scripts/tests/test_daily_report.py`: import the hyphenated production script with `importlib.util`, construct synthetic log entries, and cover aggregation, provider/cache behavior, enrichment, and HTML output without real network calls.
- Modify `scripts/daily-report.py`: add Top 3 aggregation, normalized provider/cache functions, enrichment, and the report card.
- Modify `deploy/setup-report.sh` only if needed: its existing creation of `/var/lib/lumora/stats`
  already creates the required `/var/lib/lumora` cache parent; no credentials or new
  dependencies are needed.
- Modify `deploy/README.md`: document the new Top 3 data, third-party lookup disclosure, cache location, failure behavior, and future offline provider boundary.

### Task 1: Pure Top 3 visitor aggregation

**Files:**
- Create: `scripts/tests/test_daily_report.py`
- Modify: `scripts/daily-report.py:349-432`

- [ ] **Step 1: Add the test loader and a failing aggregation test**

```python
import importlib.util
import os
import unittest

SCRIPT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "daily-report.py"))
SPEC = importlib.util.spec_from_file_location("daily_report", SCRIPT)
daily_report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(daily_report)


def entry(ip, path, status=200, ua="Mozilla/5.0"):
    return {
        "ip": ip, "path": path, "status": status, "ua": ua,
        "bytes": 10, "referer": "-",
        "time": daily_report.datetime(2026, 8, 2, tzinfo=daily_report.CST),
    }


class TopVisitorsTest(unittest.TestCase):
    def test_uses_successful_human_pages_and_stable_tie_break(self):
        rows = [
            entry("8.8.8.8", "/a"), entry("8.8.8.8", "/a", 304),
            entry("1.1.1.1", "/a"), entry("1.1.1.1", "/b"),
            entry("9.9.9.9", "/ignored", 404),
            entry("7.7.7.7", "/bot", ua="Googlebot"),
        ]
        self.assertEqual(
            daily_report.summarize(rows)["top_visitors"],
            [
                {"ip": "1.1.1.1", "hits": 2, "paths": 2},
                {"ip": "8.8.8.8", "hits": 2, "paths": 1},
            ],
        )
```

- [ ] **Step 2: Run the test and verify RED**

Run: `python3 -m unittest scripts.tests.test_daily_report.TopVisitorsTest -v`

Expected: FAIL because `top_visitors` is absent.

- [ ] **Step 3: Implement minimal deterministic aggregation**

In `summarize()`, aggregate only the existing `pages` list, construct `{"ip", "hits", "paths"}` rows, sort with `key=lambda row: (-row["hits"], row["ip"])`, and slice `[:3]`.

- [ ] **Step 4: Add edge tests and verify GREEN**

Add tests for four eligible IPs being truncated to three, fewer than three, and no eligible pages. Run:

`python3 -m unittest scripts.tests.test_daily_report.TopVisitorsTest -v`

Expected: all TopVisitors tests PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/tests/test_daily_report.py scripts/daily-report.py
git commit -m "feat(report): aggregate top visitor IPs"
```

### Task 2: Online provider, validation, and atomic cache

**Files:**
- Modify: `scripts/tests/test_daily_report.py`
- Modify: `scripts/daily-report.py:28-129` and after the existing parsing helpers

- [ ] **Step 1: Write failing provider mapping and partial-field tests**

Define tests against `OnlineGeoProvider(cache_path, transport, now)` where `transport(url, timeout)` returns bytes. Assert that `country.long_name`, top-level `region`, `city`, and `isp` map to a normalized `status="ok"` result, and that one missing field becomes `""` while other fields survive.

- [ ] **Step 2: Run the provider tests and verify RED**

Run: `python3 -m unittest scripts.tests.test_daily_report.OnlineGeoProviderTest -v`

Expected: FAIL because `OnlineGeoProvider` is absent.

- [ ] **Step 3: Implement the minimal provider and transport**

Add:

```python
GEO_CACHE_FILE = "/var/lib/lumora/geo-cache.json"
GEO_CACHE_TTL = timedelta(days=30)
GEO_API_URL = "https://ipinfo.is/%s"


def fetch_url(url, timeout):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read()
```

Implement `OnlineGeoProvider.lookup(ip)` with `ipaddress.ip_address`, rejection of every non-global category required by the spec, URL quoting suitable for IPv4/IPv6 path values, 3-second transport calls, JSON object validation, per-field string normalization, and `unavailable` when all four fields are empty. Catch network, decoding, JSON, and structural errors at the provider boundary.

- [ ] **Step 4: Verify mapping GREEN, then add failing no-network validation tests**

Test invalid, private, loopback, link-local, reserved, multicast, and unspecified addresses with a transport that fails the test if called. Expected result is `status="private"` for each.

Run: `python3 -m unittest scripts.tests.test_daily_report.OnlineGeoProviderTest -v`

Expected before validation implementation: FAIL because transport is called or status is wrong.

- [ ] **Step 5: Implement validation and verify GREEN**

Use explicit address properties rather than DNS or string prefixes. Run the provider test class and expect PASS.

- [ ] **Step 6: Add failing cache behavior tests**

Cover a fresh hit avoiding transport, expired hit calling transport, corrupt/unsupported cache behaving as a miss, one invalid cache entry being ignored without discarding other valid entries, successful lookup pruning expired rows and persisting mode `0600`, and `unavailable`/`private` results not being persisted. Use `tempfile.TemporaryDirectory()` and an injected clock.

- [ ] **Step 7: Implement cache load/save and verify GREEN**

Load defensively. Persist `{version: 1, entries: ...}` via a named file in the same directory, flush/close, `chmod(0o600)`, then `os.replace`; remove the temporary file on failure. Store timestamps as ISO-8601 values and compare them using timezone-aware datetimes supported by Python 3.6.

Run: `python3 -m unittest scripts.tests.test_daily_report.OnlineGeoProviderTest -v`

Expected: all provider tests PASS without network access.

- [ ] **Step 8: Add failure-mode tests and verify GREEN**

Test transport timeout/exception, invalid UTF-8 or JSON, non-object JSON, all target fields invalid, and an HTTP-style exception. All return `unavailable` without raising.

- [ ] **Step 9: Commit**

```bash
git add scripts/tests/test_daily_report.py scripts/daily-report.py
git commit -m "feat(report): add cached IP geolocation provider"
```

### Task 3: Explicit enrichment and HTML report card

**Files:**
- Modify: `scripts/tests/test_daily_report.py`
- Modify: `scripts/daily-report.py:481-653,709-737`

- [ ] **Step 1: Write a failing enrichment isolation test**

Create a fake provider whose `lookup()` returns different `ok`, `private`, and `unavailable` results. Assert `enrich_top_visitors(stats, provider)` calls it once per existing Top 3 row, merges normalized `geo` data without changing hits/paths, and continues after one provider raises unexpectedly.

- [ ] **Step 2: Run and verify RED**

Run: `python3 -m unittest scripts.tests.test_daily_report.EnrichmentTest -v`

Expected: FAIL because enrichment is absent.

- [ ] **Step 3: Implement enrichment and wire it only at the report entry**

Implement `enrich_top_visitors(stats, provider)` and change the entry point to
`cmd_report(args, provider=None)`. After `summarize(entries)` and before `render()`, construct
`OnlineGeoProvider(GEO_CACHE_FILE)` only when `provider is None`, then enrich. Tests can pass a
fake provider explicitly, while normal CLI dispatch keeps calling `cmd_report(args)` unchanged.
This ensures `summarize()` and module import remain network-free. Convert an unexpected provider
exception for one row to an `unavailable` result and continue.

- [ ] **Step 4: Verify enrichment GREEN**

Run the enrichment test class; expect PASS.

- [ ] **Step 5: Write failing HTML rendering tests**

Build a minimal complete `stats` fixture containing three enriched rows and assert the output includes the title “访问最多的访客”, full IP, successful hit count, distinct page count, joined non-empty location fields, ISP, “内网或保留地址”, and “归属地暂不可用”. Include HTML metacharacters in an API field and assert escaped output. Also assert the card is absent when `top_visitors=[]`.

- [ ] **Step 6: Run and verify RED**

Run: `python3 -m unittest scripts.tests.test_daily_report.ReportRenderingTest -v`

Expected: FAIL because the card is absent.

- [ ] **Step 7: Implement the report card and verify GREEN**

Add the card near the existing crawler/high-frequency sections. Render only non-empty location pieces, omit empty ISP, and escape every IP/provider string with existing `html.escape`.

Run: `python3 -m unittest scripts.tests.test_daily_report -v`

Expected: all report tests PASS.

- [ ] **Step 8: Commit**

```bash
git add scripts/tests/test_daily_report.py scripts/daily-report.py
git commit -m "feat(report): render visitor locations"
```

### Task 4: Deployment documentation and full verification

**Files:**
- Modify: `deploy/setup-report.sh:10-22`
- Modify: `deploy/README.md:153-205`

- [ ] **Step 1: Add a failing deployment contract assertion**

Inspect the existing setup contract: creating `/var/lib/lumora/stats` also creates the parent
`/var/lib/lumora` required by `/var/lib/lumora/geo-cache.json`. Do not add a redundant production
change or artificial failing test when this requirement is already satisfied. If inspection finds
the directory creation was removed or changed, first add a failing static assertion for the exact
parent path, then restore the setup behavior.

- [ ] **Step 2: Update operational documentation**

Document Top 3 successful-page semantics, `ipinfo.is` disclosure (at most three uncached public IPs per report), 30-day `0600` cache, 3-second failure degradation, cache path, and the planned `GEO_PROVIDER=online|maxmind` offline boundary. Do not claim the offline provider is implemented.

- [ ] **Step 3: Run syntax and complete local verification**

```bash
python3 -m py_compile scripts/daily-report.py
python3 -m unittest scripts.tests.test_daily_report -v
bash deploy/tests/deploy_contract_test.sh
git diff --check
```

Expected: compile succeeds, all new unit tests pass, deployment contract reports zero failures, and diff check is clean.

- [ ] **Step 4: Commit docs/setup changes if any**

```bash
git add deploy/setup-report.sh deploy/README.md deploy/tests/deploy_contract_test.sh
git commit -m "docs(report): document visitor geolocation"
```

Only include files actually changed.

- [ ] **Step 5: Review the final diff against the spec**

Check that no external lookup happens during import or `summarize()`, at most three lookups are attempted per report, cached values skip transport, no raw API payload is logged, complete IPs are confined to the requested report/cache, and existing PV/UV behavior is unchanged.

- [ ] **Step 6: Deploy the script after explicit production action is in scope**

Use the existing idempotent `./deploy/setup-report.sh` only after local verification. Confirm the remote SHA256 equals the local script and run a remote `report --date <completed-day> --dry-run`; inspect only the generated aggregate HTML section and do not expose raw access logs or visitor data in command output. Do not send an extra email.
