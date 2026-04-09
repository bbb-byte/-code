import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from ecommerce_crawler import JDPublicSatisfactionCrawler


FIXTURES = Path(__file__).parent / "fixtures"


class JDPublicSatisfactionCrawlerTest(unittest.TestCase):

    def setUp(self):
        self.crawler = JDPublicSatisfactionCrawler(timeout=1, sleep_seconds=0)

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


if __name__ == "__main__":
    unittest.main()
