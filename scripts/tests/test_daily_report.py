import argparse
import importlib.util
import json
import os
import socket
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest import mock
from urllib.error import HTTPError, URLError
from urllib.request import Request


MODULE_PATH = Path(__file__).resolve().parents[1] / "daily-report.py"
SPEC = importlib.util.spec_from_file_location("daily_report", str(MODULE_PATH))
daily_report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(daily_report)


def entry(ip, path="/", status=200, ua="Mozilla/5.0"):
    return {
        "ip": ip,
        "time": datetime(2026, 8, 3, tzinfo=timezone.utc),
        "method": "GET",
        "path": path,
        "status": status,
        "bytes": 100,
        "referer": "-",
        "ua": ua,
    }


class TopVisitorsTest(unittest.TestCase):
    def top_visitors(self, stats):
        self.assertIn("top_visitors", stats)
        return stats["top_visitors"]

    def test_only_non_bot_successful_page_requests_are_candidates(self):
        stats = daily_report.summarize([
            entry("eligible", "/ok", 200),
            entry("eligible", "/cached", 304),
            entry("not-found", "/missing", 404),
            entry("server-error", "/error", 500),
            entry("bot", "/crawled", 200, "Googlebot"),
        ])

        self.assertEqual(
            self.top_visitors(stats),
            [{"ip": "eligible", "hits": 2, "paths": 2}],
        )

    def test_sorts_by_hits_then_ip_and_truncates_to_three(self):
        stats = daily_report.summarize([
            entry("10.0.0.4", "/a"),
            entry("10.0.0.4", "/b"),
            entry("10.0.0.4", "/c"),
            entry("10.0.0.3", "/a"),
            entry("10.0.0.3", "/b"),
            entry("10.0.0.2", "/a"),
            entry("10.0.0.2", "/b"),
            entry("10.0.0.1", "/a"),
        ])

        self.assertEqual(
            self.top_visitors(stats),
            [
                {"ip": "10.0.0.4", "hits": 3, "paths": 3},
                {"ip": "10.0.0.2", "hits": 2, "paths": 2},
                {"ip": "10.0.0.3", "hits": 2, "paths": 2},
            ],
        )

    def test_counts_hits_and_deduplicates_repeated_paths(self):
        stats = daily_report.summarize([
            entry("10.0.0.1", "/same"),
            entry("10.0.0.1", "/same", 304),
            entry("10.0.0.1", "/different"),
        ])

        self.assertEqual(
            self.top_visitors(stats),
            [{"ip": "10.0.0.1", "hits": 3, "paths": 2}],
        )

    def test_returns_fewer_than_three_when_only_two_are_eligible(self):
        stats = daily_report.summarize([
            entry("10.0.0.2"),
            entry("10.0.0.2", "/second"),
            entry("10.0.0.1"),
        ])

        self.assertEqual(
            self.top_visitors(stats),
            [
                {"ip": "10.0.0.2", "hits": 2, "paths": 2},
                {"ip": "10.0.0.1", "hits": 1, "paths": 1},
            ],
        )

    def test_returns_empty_list_when_no_candidates_are_eligible(self):
        stats = daily_report.summarize([
            entry("bot", ua="Googlebot"),
            entry("missing", status=404),
        ])

        self.assertEqual(self.top_visitors(stats), [])


class EnrichmentTest(unittest.TestCase):
    class FakeProvider(object):
        def __init__(self, results):
            self.results = results
            self.calls = []

        def lookup(self, ip):
            self.calls.append(ip)
            result = self.results[ip]
            if isinstance(result, Exception):
                raise result
            return result

    def test_enriches_each_existing_row_once_and_preserves_counts(self):
        stats = {"top_visitors": [
            {"ip": "8.8.8.8", "hits": 7, "paths": 3},
            {"ip": "10.0.0.1", "hits": 4, "paths": 2},
            {"ip": "1.1.1.1", "hits": 2, "paths": 1},
        ]}
        provider = self.FakeProvider({
            "8.8.8.8": {"country": "US", "region": "CA", "city": "LA",
                         "isp": "ISP", "status": "ok"},
            "10.0.0.1": {"country": "", "region": "", "city": "",
                           "isp": "", "status": "private"},
            "1.1.1.1": {"country": "", "region": "", "city": "",
                         "isp": "", "status": "unavailable"},
        })

        daily_report.enrich_top_visitors(stats, provider)

        self.assertEqual(provider.calls, ["8.8.8.8", "10.0.0.1", "1.1.1.1"])
        self.assertEqual(stats["top_visitors"][0], {
            "ip": "8.8.8.8", "hits": 7, "paths": 3,
            "geo": {"country": "US", "region": "CA", "city": "LA",
                    "isp": "ISP", "status": "ok"},
        })
        self.assertEqual(stats["top_visitors"][1]["geo"]["status"], "private")
        self.assertEqual(stats["top_visitors"][2]["geo"]["status"], "unavailable")

    def test_lookup_exception_becomes_unavailable_and_remaining_rows_continue(self):
        stats = {"top_visitors": [
            {"ip": "bad", "hits": 2, "paths": 1},
            {"ip": "good", "hits": 1, "paths": 1},
        ]}
        provider = self.FakeProvider({
            "bad": RuntimeError("unexpected"),
            "good": {"country": "China", "region": "", "city": "",
                     "isp": "", "status": "ok"},
        })

        daily_report.enrich_top_visitors(stats, provider)

        self.assertEqual(provider.calls, ["bad", "good"])
        self.assertEqual(stats["top_visitors"][0]["geo"], {
            "country": "", "region": "", "city": "", "isp": "",
            "status": "unavailable",
        })
        self.assertEqual(stats["top_visitors"][1]["geo"]["country"], "China")

    def test_empty_rows_make_no_calls_and_only_first_three_are_enriched(self):
        empty_provider = self.FakeProvider({})
        daily_report.enrich_top_visitors({"top_visitors": []}, empty_provider)
        self.assertEqual(empty_provider.calls, [])

        rows = [{"ip": str(index), "hits": 1, "paths": 1} for index in range(4)]
        provider = self.FakeProvider({
            str(index): {"country": "", "region": "", "city": "", "isp": "",
                         "status": "unavailable"}
            for index in range(3)
        })
        daily_report.enrich_top_visitors({"top_visitors": rows}, provider)
        self.assertEqual(provider.calls, ["0", "1", "2"])
        self.assertNotIn("geo", rows[3])


class ReportRenderingTest(unittest.TestCase):
    def stats(self, visitors):
        stats = daily_report.summarize([])
        stats["top_visitors"] = visitors
        return stats

    def test_renders_ok_private_and_unavailable_locations_and_escapes_strings(self):
        visitors = [
            {"ip": '<8.8.8.8&"full">', "hits": 9, "paths": 4,
             "geo": {"country": "US<script>", "region": "", "city": "LA&West",
                     "isp": 'ISP "One"', "status": "ok"}},
            {"ip": "10.0.0.1", "hits": 3, "paths": 2,
             "geo": {"country": "", "region": "", "city": "", "isp": "",
                     "status": "private"}},
            {"ip": "1.1.1.1", "hits": 1, "paths": 1,
             "geo": {"country": "", "region": "", "city": "", "isp": "",
                     "status": "unavailable"}},
        ]

        body = daily_report.render(datetime(2026, 8, 3).date(), self.stats(visitors), None)

        self.assertIn("访问最多的访客", body)
        self.assertIn('&lt;8.8.8.8&amp;&quot;full&quot;&gt;', body)
        self.assertIn("US&lt;script&gt; · LA&amp;West", body)
        self.assertNotIn("US&lt;script&gt; ·  · LA", body)
        self.assertIn("ISP &quot;One&quot;", body)
        self.assertIn("内网或保留地址", body)
        self.assertIn("归属地暂不可用", body)
        self.assertIn("9 次成功访问", body)
        self.assertIn("4 个不同页面", body)

    def test_ok_with_all_empty_geo_fields_is_unavailable_and_empty_isp_is_omitted(self):
        visitor = {"ip": "8.8.4.4", "hits": 1, "paths": 1,
                   "geo": {"country": "", "region": "", "city": "", "isp": "",
                           "status": "ok"}}
        body = daily_report.render(datetime(2026, 8, 3).date(), self.stats([visitor]), None)
        self.assertIn("归属地暂不可用", body)
        self.assertNotIn("ISP：", body)

    def test_unavailable_status_ignores_all_residual_geo_fields(self):
        visitor = {"ip": "1.1.1.1", "hits": 1, "paths": 1,
                   "geo": {"country": "StaleCountry", "region": "StaleRegion",
                           "city": "StaleCity", "isp": "StaleISP",
                           "status": "unavailable"}}
        body = daily_report.render(datetime(2026, 8, 3).date(), self.stats([visitor]), None)
        self.assertIn("归属地暂不可用", body)
        for stale_value in ("StaleCountry", "StaleRegion", "StaleCity", "StaleISP"):
            self.assertNotIn(stale_value, body)

    def test_private_status_ignores_all_residual_geo_fields(self):
        visitor = {"ip": "10.0.0.1", "hits": 1, "paths": 1,
                   "geo": {"country": "WrongCountry", "region": "WrongRegion",
                           "city": "WrongCity", "isp": "WrongISP",
                           "status": "private"}}
        body = daily_report.render(datetime(2026, 8, 3).date(), self.stats([visitor]), None)
        self.assertIn("内网或保留地址", body)
        for wrong_value in ("WrongCountry", "WrongRegion", "WrongCity", "WrongISP"):
            self.assertNotIn(wrong_value, body)

    def test_visitor_card_is_absent_when_there_are_no_top_visitors(self):
        body = daily_report.render(datetime(2026, 8, 3).date(), self.stats([]), None)
        self.assertNotIn("访问最多的访客", body)

    def test_cmd_report_dry_run_uses_injected_provider(self):
        provider = EnrichmentTest.FakeProvider({
            "8.8.8.8": {"country": "US", "region": "", "city": "",
                         "isp": "", "status": "ok"},
        })
        args = argparse.Namespace(date="2026-08-03", dry_run=True, out="report.html")
        with tempfile.TemporaryDirectory() as tempdir:
            args.out = os.path.join(tempdir, "report.html")
            with mock.patch.object(daily_report, "load_day", return_value=[entry("8.8.8.8")]), \
                    mock.patch.object(daily_report, "load_previous", return_value=None):
                self.assertEqual(daily_report.cmd_report(args, provider=provider), 0)
            with open(args.out) as report_file:
                body = report_file.read()
        self.assertEqual(provider.calls, ["8.8.8.8"])
        self.assertIn("访问最多的访客", body)


class OnlineGeoProviderTest(unittest.TestCase):
    NOW = datetime(2026, 8, 3, 12, 0, tzinfo=timezone.utc)

    def setUp(self):
        self.tempdir = tempfile.TemporaryDirectory()
        self.cache_path = os.path.join(self.tempdir.name, "geo-cache.json")
        self.urls = []

    def tearDown(self):
        self.tempdir.cleanup()

    def provider(self, payload=None, transport=None, now=None):
        if transport is None:
            raw = json.dumps(payload).encode("utf-8")

            def transport(url, timeout):
                self.urls.append((url, timeout))
                return raw
        return daily_report.OnlineGeoProvider(
            cache_path=self.cache_path,
            transport=transport,
            now=now or (lambda: self.NOW),
        )

    def write_cache(self, entries, version=1):
        with open(self.cache_path, "w") as cache_file:
            json.dump({"version": version, "entries": entries}, cache_file)

    def cache_row(self, when, country="Cached"):
        return {
            "looked_up_at": when.isoformat(), "country": country,
            "region": "R", "city": "C", "isp": "I",
        }

    def test_maps_complete_api_response(self):
        result = self.provider({
            "country": {"long_name": "United States"},
            "region": "California",
            "city": "Los Angeles",
            "isp": "Example ISP",
        }).lookup("8.8.8.8")

        self.assertEqual(result, {
            "country": "United States", "region": "California",
            "city": "Los Angeles", "isp": "Example ISP", "status": "ok",
        })
        self.assertEqual(self.urls, [("https://ipinfo.is/8.8.8.8", 3)])

    def test_accepts_partial_string_fields_and_quotes_ipv6_path(self):
        result = self.provider({
            "country": {"long_name": 123}, "region": "Europe",
            "city": None, "isp": [],
        }).lookup("2001:4860:4860::8888")

        self.assertEqual(result, {
            "country": "", "region": "Europe", "city": "", "isp": "",
            "status": "ok",
        })
        self.assertEqual(
            self.urls,
            [("https://ipinfo.is/2001%3A4860%3A4860%3A%3A8888", 3)],
        )

    def test_invalid_and_non_global_addresses_never_use_network(self):
        called = []

        def transport(url, timeout):
            called.append(url)
            raise AssertionError("network should not be called")

        provider = self.provider(transport=transport)
        addresses = [
            "not-an-ip", "10.0.0.1", "127.0.0.1", "169.254.1.1",
            "192.0.2.1", "224.0.0.1", "0.0.0.0", "::1", "fe80::1",
            "ff02::1", "::",
        ]
        for address in addresses:
            with self.subTest(address=address):
                self.assertEqual(provider.lookup(address)["status"], "private")
        self.assertEqual(called, [])

    def test_fresh_cache_skips_transport_and_ignores_invalid_sibling(self):
        self.write_cache({
            "8.8.8.8": self.cache_row(self.NOW - timedelta(days=29)),
            "1.1.1.1": {"looked_up_at": "bad", "country": 42},
        })

        def no_network(url, timeout):
            raise AssertionError("fresh cache should skip transport")

        result = self.provider(transport=no_network).lookup("8.8.8.8")
        self.assertEqual(result, {
            "country": "Cached", "region": "R", "city": "C", "isp": "I",
            "status": "ok",
        })

    def test_expired_entry_refreshes_prunes_and_saves_mode_0600(self):
        self.write_cache({
            "8.8.8.8": self.cache_row(self.NOW - timedelta(days=31), "Old"),
            "1.1.1.1": self.cache_row(self.NOW - timedelta(days=31)),
            "9.9.9.9": self.cache_row(self.NOW - timedelta(days=1), "Keep"),
            "bad": self.cache_row(self.NOW - timedelta(days=1), "Bad"),
        })
        result = self.provider({"city": "New City"}).lookup("8.8.8.8")

        self.assertEqual(result["city"], "New City")
        with open(self.cache_path) as cache_file:
            saved = json.load(cache_file)
        self.assertEqual(saved["version"], 1)
        self.assertEqual(set(saved["entries"]), {"8.8.8.8", "9.9.9.9"})
        self.assertEqual(saved["entries"]["8.8.8.8"]["looked_up_at"],
                         self.NOW.isoformat())
        self.assertEqual(os.stat(self.cache_path).st_mode & 0o777, 0o600)
        self.assertEqual([name for name in os.listdir(self.tempdir.name)
                          if name != "geo-cache.json"], [])

    def test_corrupt_and_unsupported_caches_are_misses(self):
        cases = ["not json", json.dumps({"version": 2, "entries": {}})]
        for index, contents in enumerate(cases):
            with self.subTest(index=index):
                with open(self.cache_path, "w") as cache_file:
                    cache_file.write(contents)
                self.urls[:] = []
                result = self.provider({"country": {"long_name": "Online"}}).lookup("8.8.8.8")
                self.assertEqual(result["status"], "ok")
                self.assertEqual(len(self.urls), 1)

    def test_private_and_unavailable_results_are_not_persisted(self):
        self.assertEqual(self.provider({}).lookup("8.8.8.8")["status"], "unavailable")
        self.assertFalse(os.path.exists(self.cache_path))
        self.assertEqual(self.provider({}).lookup("127.0.0.1")["status"], "private")
        self.assertFalse(os.path.exists(self.cache_path))

    def test_cache_write_failure_does_not_hide_successful_lookup(self):
        bad_path = os.path.join(self.tempdir.name, "missing", "cache.json")
        provider = daily_report.OnlineGeoProvider(
            cache_path=bad_path,
            transport=lambda url, timeout: b'{"city":"Online"}',
            now=lambda: self.NOW,
        )
        self.assertEqual(provider.lookup("8.8.8.8")["status"], "ok")

    def test_transport_decode_json_and_structure_failures_are_unavailable(self):
        failures = [
            lambda url, timeout: (_ for _ in ()).throw(socket.timeout()),
            lambda url, timeout: (_ for _ in ()).throw(URLError("offline")),
            lambda url, timeout: (_ for _ in ()).throw(
                HTTPError(url, 500, "server error", {}, None)),
            lambda url, timeout: b"\xff",
            lambda url, timeout: b"{broken",
            lambda url, timeout: b"[]",
            lambda url, timeout: json.dumps({
                "country": {"long_name": 1}, "region": None,
                "city": [], "isp": {},
            }).encode("utf-8"),
            lambda url, timeout: "not bytes",
        ]
        for index, transport in enumerate(failures):
            with self.subTest(index=index):
                result = self.provider(transport=transport).lookup("8.8.8.8")
                self.assertEqual(result, {
                    "country": "", "region": "", "city": "", "isp": "",
                    "status": "unavailable",
                })
                self.assertFalse(os.path.exists(self.cache_path))

    def test_default_transport_identifies_request_and_returns_bytes(self):
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b"response"
        with mock.patch.object(daily_report, "urlopen", return_value=response) as opener:
            self.assertEqual(daily_report._geo_transport("https://example.test", 3),
                             b"response")
        request = opener.call_args[0][0]
        self.assertIsInstance(request, Request)
        self.assertEqual(request.full_url, "https://example.test")
        self.assertEqual(request.get_header("User-agent"),
                         "Lumora-Daily-Report/1.0")
        self.assertEqual(opener.call_args[1], {"timeout": 3})

    def test_atomic_replace_failure_cleans_temporary_file(self):
        with mock.patch.object(daily_report.os, "replace", side_effect=OSError("fail")):
            result = self.provider({"city": "Online"}).lookup("8.8.8.8")
        self.assertEqual(result["status"], "ok")
        self.assertEqual(os.listdir(self.tempdir.name), [])

    def test_cache_timestamp_with_microseconds_is_fresh(self):
        current = self.NOW.replace(microsecond=123456)
        self.write_cache({"8.8.8.8": self.cache_row(current - timedelta(days=1))})
        provider = self.provider(
            transport=lambda url, timeout: (_ for _ in ()).throw(
                AssertionError("cache should be used")),
            now=lambda: current,
        )
        self.assertEqual(provider.lookup("8.8.8.8")["status"], "ok")


if __name__ == "__main__":
    unittest.main()
