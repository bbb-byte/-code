import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from mapping_scorer import load_candidates, load_products, score_candidates


class MappingScorerTest(unittest.TestCase):

    def write_csv(self, path: Path, header, rows) -> None:
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(header)
            writer.writerows(rows)

    def test_scores_high_confidence_candidate(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            products_path = base / "products.csv"
            candidates_path = base / "candidates.csv"

            self.write_csv(
                products_path,
                ["item_id", "brand", "category_name", "price"],
                [[44600062, "shiseido", "beauty/skincare", "35.79"]],
            )
            self.write_csv(
                candidates_path,
                [
                    "item_id",
                    "source_platform",
                    "source_product_id",
                    "source_url",
                    "public_title",
                    "public_brand",
                    "public_category",
                    "public_price",
                ],
                [[
                    44600062,
                    "jd",
                    "100012043978",
                    "https://item.jd.com/100012043978.html",
                    "Shiseido skincare essence",
                    "shiseido",
                    "beauty/skincare",
                    "36.50",
                ]],
            )

            rows = score_candidates(load_products(products_path), load_candidates(candidates_path))

            self.assertEqual(1, len(rows))
            self.assertEqual("1.00", rows[0]["total_score"])
            self.assertEqual("fast_review", rows[0]["recommended_action"])

    def test_caps_score_when_internal_price_is_missing(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            products_path = base / "products.csv"
            candidates_path = base / "candidates.csv"

            self.write_csv(
                products_path,
                ["item_id", "brand", "category_name", "price"],
                [[4820710, "kindle", "books/reader", ""]],
            )
            self.write_csv(
                candidates_path,
                [
                    "item_id",
                    "source_platform",
                    "source_product_id",
                    "source_url",
                    "public_title",
                    "public_brand",
                    "public_category",
                    "public_price",
                ],
                [[
                    4820710,
                    "jd",
                    "10009999",
                    "https://item.jd.com/10009999.html",
                    "Kindle reader classic",
                    "kindle",
                    "books/reader",
                    "999.00",
                ]],
            )

            rows = score_candidates(load_products(products_path), load_candidates(candidates_path))

            self.assertEqual(1, len(rows))
            self.assertEqual("0.85", rows[0]["total_score"])
            self.assertEqual("manual_review", rows[0]["recommended_action"])

    def test_rejects_low_match_candidate(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            products_path = base / "products.csv"
            candidates_path = base / "candidates.csv"

            self.write_csv(
                products_path,
                ["item_id", "brand", "category_name", "price"],
                [[10001, "nike", "sports/shoes", "300"]],
            )
            self.write_csv(
                candidates_path,
                [
                    "item_id",
                    "source_platform",
                    "source_product_id",
                    "source_url",
                    "public_title",
                    "public_brand",
                    "public_category",
                    "public_price",
                ],
                [[
                    10001,
                    "jd",
                    "20008888",
                    "https://item.jd.com/20008888.html",
                    "Kitchen rice cooker",
                    "midea",
                    "home/appliance",
                    "899.00",
                ]],
            )

            rows = score_candidates(load_products(products_path), load_candidates(candidates_path))

            self.assertEqual(1, len(rows))
            self.assertEqual("0.10", rows[0]["total_score"])
            self.assertEqual("reject", rows[0]["recommended_action"])


if __name__ == "__main__":
    unittest.main()
