import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent))

from ecommerce_crawler import (
    CONSECUTIVE_BLOCK_THRESHOLD,
    JDPublicSatisfactionCrawler,
)


FIXTURES = Path(__file__).parent / "fixtures"


class JDPublicSatisfactionCrawlerTest(unittest.TestCase):

    def setUp(self):
        self.crawler = JDPublicSatisfactionCrawler(
            timeout=1, sleep_seconds=0, fixture_dir=FIXTURES,
        )

    def test_extract_metrics_from_fixture(self):
        payload = self.crawler.parse_summary_payload(
            (FIXTURES / "jd_comment_summary.sample.json").read_text(encoding="utf-8")
        )[0]
        html = (FIXTURES / "jd_product_page.sample.html").read_text(encoding="utf-8")

        metrics = self.crawler.extract_metrics(payload, html)

        self.assertEqual(0.973, metrics["positive_rate"])
        self.assertEqual(12890, metrics["review_count"])
        self.assertEqual(4.8, metrics["shop_score"])
        self.assertEqual("好评率 97%", metrics["rating_text"])

    def test_load_mapping_rows_accepts_numeric_confidence_sample(self):
        mapping_path = Path(__file__).parent / "mappings" / "product_public_mapping.sample.csv"

        rows = self.crawler.load_mapping_rows(mapping_path)

        self.assertEqual(1, len(rows))
        self.assertEqual(0.95, rows[0].mapping_confidence)

    def test_load_mapping_rows_validates_jd_only(self):
        mapping_path = Path(__file__).parent / "mappings" / "product_public_mapping.jd.sample.csv"

        rows = self.crawler.load_mapping_rows(mapping_path)

        self.assertEqual(2, len(rows))
        self.assertEqual("jd", rows[0].source_platform)
        self.assertTrue(rows[0].source_url.startswith("https://item.jd.com/"))

    def test_identifies_transient_block_response(self):
        self.assertTrue(self.crawler.is_transient_blocked_response("系统繁忙"))
        self.assertTrue(self.crawler.is_transient_blocked_response("<html>blocked</html>"))
        self.assertFalse(self.crawler.is_transient_blocked_response('{"CommentsCount": []}'))

    def test_consecutive_blocks_counter(self):
        self.assertEqual(0, self.crawler._consecutive_blocks)
        self.crawler._on_block_detected()
        self.assertEqual(1, self.crawler._consecutive_blocks)
        self.crawler._on_success()
        self.assertEqual(0, self.crawler._consecutive_blocks)

    @patch("time.sleep")
    def test_consecutive_blocks_triggers_pause(self, mock_sleep):
        """连续拦截达到阈值时应触发长时间暂停。"""
        crawler = JDPublicSatisfactionCrawler(
            timeout=1, sleep_seconds=0, fixture_dir=FIXTURES,
        )
        for _ in range(CONSECUTIVE_BLOCK_THRESHOLD):
            crawler._on_block_detected()
        # 暂停后计数器应重置
        self.assertEqual(0, crawler._consecutive_blocks)
        self.assertTrue(mock_sleep.called)

    def test_parse_jsonp_plain_json(self):
        result = self.crawler._parse_jsonp('{"CommentsCount":[{"goodRate":0.97}]}')
        self.assertEqual(0.97, result["CommentsCount"][0]["goodRate"])

    def test_parse_jsonp_wrapped(self):
        result = self.crawler._parse_jsonp('callback({"CommentsCount":[{"goodRate":0.97}]})')
        self.assertEqual(0.97, result["CommentsCount"][0]["goodRate"])

    def test_fixture_mode_fetch(self):
        """fixture 模式下应从本地文件获取数据。"""
        row = self.crawler.load_mapping_rows(
            Path(__file__).parent / "mappings" / "product_public_mapping.jd.sample.csv"
        )[0]
        summary = self.crawler.fetch_comment_summary_via_browser(row)
        # fixture 文件 jd_comment_summary.sample.json 应返回数据
        self.assertIn("goodRate", summary)

    def test_extract_positive_rate_from_summary(self):
        summary = {"goodRate": 0.973}
        result = self.crawler.extract_positive_rate(summary, "")
        self.assertEqual(0.973, result)

    def test_extract_positive_rate_percentage(self):
        summary = {"goodRateShow": 97.3}
        result = self.crawler.extract_positive_rate(summary, "")
        self.assertEqual(0.973, result)

    def test_extract_review_count(self):
        summary = {"commentCount": 12890}
        result = self.crawler.extract_review_count(summary)
        self.assertEqual(12890, result)

    def test_parse_count_string_wan(self):
        result = self.crawler.parse_count_string("1.5万+")
        self.assertEqual(15000, result)


if __name__ == "__main__":
    unittest.main()
