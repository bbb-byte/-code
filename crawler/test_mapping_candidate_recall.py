import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from mapping_candidate_recall import JDCandidateRecall


FIXTURES = Path(__file__).parent / "fixtures"


class MappingCandidateRecallTest(unittest.TestCase):

    def test_build_keyword_uses_brand_and_leaf_category(self):
        recall = JDCandidateRecall()

        keyword = recall.build_keyword("shiseido", "beauty/skincare")

        self.assertEqual("shiseido skincare", keyword)

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
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            products_path = base / "products.csv"
            output_path = base / "candidates.csv"

            with products_path.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["item_id", "brand", "category_name", "price"])
                writer.writerow([44600062, "shiseido", "beauty/skincare", "35.79"])

            subprocess.run(
                [
                    "python3",
                    "crawler/mapping_candidate_recall.py",
                    "--products",
                    str(products_path),
                    "--fixture-dir",
                    "crawler/fixtures",
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


if __name__ == "__main__":
    unittest.main()
