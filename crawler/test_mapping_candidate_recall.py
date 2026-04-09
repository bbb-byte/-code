import csv
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).parent))

from mapping_candidate_recall import DEFAULT_HEADERS, JDCandidateRecall


FIXTURES = Path(__file__).parent / "fixtures"


class MappingCandidateRecallTest(unittest.TestCase):

    def test_build_keyword_uses_brand_and_leaf_category(self):
        recall = JDCandidateRecall()

        keyword = recall.build_keyword("shiseido", "beauty/skincare")

        self.assertEqual("shiseido skincare", keyword)

    def test_build_keyword_uses_leaf_category_for_dot_separated_names(self):
        recall = JDCandidateRecall()

        keyword = recall.build_keyword("apple", "electronics.smartphone")

        self.assertEqual("apple smartphone", keyword)

    def test_parse_search_results_extracts_top_candidates(self):
        recall = JDCandidateRecall(fixture_dir=FIXTURES)
        html = (FIXTURES / "jd_search_result.sample.html").read_text(encoding="utf-8")

        rows = recall.parse_search_results(
            html=html,
            brand_hint="shiseido",
            category_hint="beauty/skincare",
            top_k=2,
        )

        self.assertEqual(2, len(rows))
        self.assertEqual("100012043978", rows[0].source_product_id)
        self.assertEqual("https://item.jd.com/100012043978.html", rows[0].source_url)
        self.assertEqual("shiseido", rows[0].public_brand)
        self.assertEqual("beauty/skincare", rows[0].public_category)
        self.assertEqual(36.5, rows[0].public_price)
        self.assertEqual(1, rows[0].rank)

    def test_cli_generates_candidate_csv_from_fixture(self):
        output_root = Path(__file__).parent / "output"
        output_root.mkdir(exist_ok=True)
        base = output_root / "test_cli_generates_candidate_csv_from_fixture"
        base.mkdir(exist_ok=True)
        products_path = base / "products.csv"
        output_path = base / "candidates.csv"

        with products_path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(["item_id", "brand", "category_name", "price"])
            writer.writerow([44600062, "shiseido", "beauty/skincare", "35.79"])

        subprocess.run(
            [
                sys.executable,
                "crawler/mapping_candidate_recall.py",
                "--products",
                str(products_path),
                "--fixture-dir",
                "crawler/fixtures",
                "--allow-sample-fallback",
                "--output",
                str(output_path),
                "--top-k",
                "2",
            ],
            cwd=Path(__file__).resolve().parent.parent,
            check=True,
        )

        rows = output_path.read_text(encoding="utf-8").strip().splitlines()
        self.assertEqual(3, len(rows))
        self.assertIn("100012043978", rows[1])
        self.assertIn("jd_search", rows[1])

    def test_session_uses_browser_like_default_headers(self):
        recall = JDCandidateRecall(fixture_dir=None)

        session = recall.get_session()

        self.assertEqual(DEFAULT_HEADERS["Accept-Language"], session.headers["Accept-Language"])
        self.assertIn("Mozilla/5.0", session.headers["User-Agent"])

    def test_wait_for_request_slot_respects_global_delay(self):
        recall = JDCandidateRecall(request_delay=0.5)

        with patch("mapping_candidate_recall.time.monotonic", side_effect=[10.0, 10.2]), \
                patch("mapping_candidate_recall.time.sleep") as mock_sleep:
            recall.wait_for_request_slot()
            recall.wait_for_request_slot()

        mock_sleep.assert_called_once()
        self.assertAlmostEqual(0.3, mock_sleep.call_args.args[0], places=6)

    def test_fetch_search_page_uses_live_request_when_fixture_dir_is_empty(self):
        mock_response = Mock()
        mock_response.text = "<html></html>"
        mock_response.raise_for_status = Mock()

        recall = JDCandidateRecall(fixture_dir=None)
        with patch.object(recall, "wait_for_request_slot") as mock_wait, \
                patch.object(recall.get_session(), "get", return_value=mock_response) as mock_get:
            html = recall.fetch_search_page(123, "apple smartphone")

        self.assertEqual("<html></html>", html)
        mock_wait.assert_called_once()
        mock_get.assert_called_once()
        _, kwargs = mock_get.call_args
        self.assertEqual({"keyword": "apple smartphone"}, kwargs["params"])
        self.assertEqual(
            "https://search.jd.com/Search?keyword=apple+smartphone",
            kwargs["headers"]["Referer"],
        )


if __name__ == "__main__":
    unittest.main()
