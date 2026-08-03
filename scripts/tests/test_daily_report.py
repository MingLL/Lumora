import importlib.util
import unittest
from datetime import datetime, timezone
from pathlib import Path


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


if __name__ == "__main__":
    unittest.main()
