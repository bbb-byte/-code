import csv
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from build_internal_products_snapshot import build_snapshots


class BuildInternalProductsSnapshotTest(unittest.TestCase):

    def write_csv(self, path: Path, header, rows) -> None:
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle)
            writer.writerow(header)
            writer.writerows(rows)

    def test_build_snapshots_deduplicates_by_item_id(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            input_path = Path(temp_dir) / "events.csv"
            self.write_csv(
                input_path,
                ["event_time", "event_type", "product_id", "category_code", "brand", "price", "user_id"],
                [
                    ["2019-10-01 00:00:00 UTC", "view", "44600062", "", "shiseido", "35.79", "1"],
                    ["2019-10-01 00:00:01 UTC", "cart", "44600062", "beauty/skincare", "shiseido", "", "2"],
                    ["2019-10-01 00:00:02 UTC", "view", "3900821", "appliances.environment.water_heater", "aqua", "33.20", "3"],
                ],
            )

            snapshots = build_snapshots(input_path)

            self.assertEqual(2, len(snapshots))
            self.assertEqual("shiseido", snapshots[44600062].brand)
            self.assertEqual("beauty/skincare", snapshots[44600062].category_name)
            self.assertEqual(35.79, snapshots[44600062].price)

    def test_cli_generates_internal_products_csv(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            base = Path(temp_dir)
            input_path = base / "events.csv"
            output_path = base / "internal_products.csv"
            self.write_csv(
                input_path,
                ["event_time", "event_type", "product_id", "category_code", "brand", "price", "user_id"],
                [
                    ["2019-10-01 00:00:00 UTC", "view", "44600062", "", "shiseido", "35.79", "1"],
                    ["2019-10-01 00:00:01 UTC", "cart", "44600062", "beauty/skincare", "shiseido", "", "2"],
                ],
            )

            subprocess.run(
                [
                    "python3",
                    "crawler/build_internal_products_snapshot.py",
                    "--input",
                    str(input_path),
                    "--output",
                    str(output_path),
                ],
                cwd=Path(__file__).resolve().parent.parent,
                check=True,
            )

            rows = output_path.read_text(encoding="utf-8").strip().splitlines()
            self.assertEqual(2, len(rows))
            self.assertIn("item_id,brand,category_name,price", rows[0])
            self.assertIn("44600062,shiseido,beauty/skincare,35.79", rows[1])


if __name__ == "__main__":
    unittest.main()
