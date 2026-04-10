import csv
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from mapping_candidate_recall import JDCandidateRecall


FIXTURES = Path(__file__).parent / "fixtures"


class MappingCandidateRecallTest(unittest.TestCase):

    def test_build_keyword_uses_brand_and_leaf_category(self):
        recall = JDCandidateRecall(fixture_dir=FIXTURES)

        keyword = recall.build_keyword("shiseido", "beauty/skincare")

        self.assertEqual("shiseido skincare", keyword)

    def test_build_keyword_uses_leaf_category_for_dot_separated_names(self):
        recall = JDCandidateRecall(fixture_dir=FIXTURES)

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

    def test_is_risk_page_detects_frequency_block(self):
        recall = JDCandidateRecall(fixture_dir=FIXTURES)

        self.assertTrue(recall.is_risk_page("访问频繁，请稍后再试"))
        self.assertTrue(recall.is_risk_page('<html>jdr_shields</html>'))
        self.assertFalse(recall.is_risk_page('<html><body>normal</body></html>'))

    def test_fetch_search_page_from_fixture(self):
        recall = JDCandidateRecall(fixture_dir=FIXTURES, allow_sample_fallback=True)

        html = recall.fetch_search_page(999, "test keyword")

        self.assertIn("gl-item", html)
        self.assertEqual(1, recall.shared_sample_hits)


if __name__ == "__main__":
    unittest.main()
