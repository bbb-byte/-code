import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, Optional, Sequence


@dataclass
class ProductSnapshot:
    item_id: int
    brand: str
    category_name: str
    price: Optional[float]

    def merge(self, other: "ProductSnapshot") -> "ProductSnapshot":
        return ProductSnapshot(
            item_id=self.item_id,
            brand=pick_better_text(self.brand, other.brand),
            category_name=pick_better_text(self.category_name, other.category_name),
            price=pick_better_price(self.price, other.price),
        )


def normalize_header(header: str) -> str:
    return "".join(ch for ch in (header or "").strip().lower() if ch.isalnum())


def value_of(row: Dict[str, str], aliases: Sequence[str]) -> str:
    for alias in aliases:
        value = row.get(alias)
        if value is not None:
            return value
    return ""


def parse_int(value: str) -> Optional[int]:
    raw = (value or "").strip()
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def parse_float(value: str) -> Optional[float]:
    raw = (value or "").strip()
    if not raw:
        return None
    try:
        numeric = float(raw.replace(",", "").replace("¥", "").replace("元", ""))
        return numeric if numeric > 0 else None
    except ValueError:
        return None


def pick_better_text(current: str, incoming: str) -> str:
    current_clean = (current or "").strip()
    incoming_clean = (incoming or "").strip()
    if not current_clean:
        return incoming_clean
    if not incoming_clean:
        return current_clean
    if len(incoming_clean) > len(current_clean):
        return incoming_clean
    return current_clean


def pick_better_price(current: Optional[float], incoming: Optional[float]) -> Optional[float]:
    if current is None:
        return incoming
    if incoming is None:
        return current
    return current


def iter_rows(path: Path) -> Iterable[Dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        normalized_headers = [normalize_header(name) for name in (reader.fieldnames or [])]
        for raw_row in reader:
            row: Dict[str, str] = {}
            for field_name, value in zip(normalized_headers, raw_row.values()):
                row[field_name] = value or ""
            yield row


def build_snapshots(input_path: Path) -> Dict[int, ProductSnapshot]:
    snapshots: Dict[int, ProductSnapshot] = {}
    for row in iter_rows(input_path):
        item_id = parse_int(value_of(row, ("itemid", "productid", "goodsid")))
        if item_id is None:
            continue
        snapshot = ProductSnapshot(
            item_id=item_id,
            brand=(value_of(row, ("brand", "brandname")) or "").strip(),
            category_name=(value_of(row, ("categoryname", "categorycode")) or "").strip(),
            price=parse_float(value_of(row, ("price", "unitprice", "amount"))),
        )
        existing = snapshots.get(item_id)
        snapshots[item_id] = snapshot if existing is None else existing.merge(snapshot)
    return snapshots


def write_snapshots(output_path: Path, snapshots: Dict[int, ProductSnapshot]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["item_id", "brand", "category_name", "price"])
        for item_id in sorted(snapshots.keys()):
            snapshot = snapshots[item_id]
            writer.writerow([
                snapshot.item_id,
                snapshot.brand,
                snapshot.category_name,
                "" if snapshot.price is None else f"{snapshot.price:.2f}",
            ])


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="从 Kaggle/archive 原始行为 CSV 生成商品快照 CSV")
    parser.add_argument("--input", required=True, help="原始行为 CSV 路径")
    parser.add_argument("--output", required=True, help="商品快照 CSV 路径")
    return parser


def resolve_path(base_dir: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    if path.parts and path.parts[0] == base_dir.name:
        return (base_dir.parent / path).resolve()
    if path.exists():
        return path.resolve()
    cwd_candidate = (Path.cwd() / path).resolve()
    if cwd_candidate.parent.exists():
        return cwd_candidate
    return (base_dir / path).resolve()


def main() -> None:
    args = build_parser().parse_args()
    base_dir = Path(__file__).resolve().parent
    input_path = resolve_path(base_dir, args.input)
    output_path = resolve_path(base_dir, args.output)

    snapshots = build_snapshots(input_path)
    write_snapshots(output_path, snapshots)

    print(f"已读取原始行为文件: {input_path}")
    print(f"已生成 {len(snapshots)} 条商品快照。")
    print(f"输出文件: {output_path}")
    print("说明：该脚本用于把事件级 Kaggle/archive 数据整理为商品级快照。")


if __name__ == "__main__":
    main()
