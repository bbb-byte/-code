import argparse
import csv
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Set


GENERIC_CATEGORY_TOKENS = {
    "item",
    "items",
    "product",
    "products",
    "goods",
    "category",
}


@dataclass
class InternalProduct:
    item_id: int
    brand: str
    category_name: str
    price: Optional[float]


@dataclass
class CandidateProduct:
    item_id: int
    source_platform: str
    source_product_id: str
    source_url: str
    public_title: str
    public_brand: str
    public_category: str
    public_price: Optional[float]


def normalize_header(header: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (header or "").strip().lower())


def normalize_text(value: str) -> str:
    lowered = (value or "").strip().lower()
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", " ", lowered).strip()


def simplify_text(value: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", (value or "").strip().lower())


def tokenize(value: str) -> Set[str]:
    return {token for token in normalize_text(value).split() if token}


def parse_float(value: str) -> Optional[float]:
    raw = (value or "").strip()
    if not raw:
        return None
    try:
        return float(raw.replace(",", "").replace("¥", "").replace("元", ""))
    except ValueError:
        return None


def parse_int(value: str) -> Optional[int]:
    raw = (value or "").strip()
    if not raw:
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def value_of(row: Dict[str, str], aliases: Sequence[str]) -> str:
    for alias in aliases:
        value = row.get(alias)
        if value is not None:
            return value
    return ""


def read_csv_rows(path: Path) -> Iterable[Dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        normalized_fieldnames = [normalize_header(name) for name in (reader.fieldnames or [])]
        for raw_row in reader:
            normalized_row: Dict[str, str] = {}
            for fieldname, value in zip(normalized_fieldnames, raw_row.values()):
                normalized_row[fieldname] = value or ""
            yield normalized_row


def load_products(path: Path) -> Dict[int, InternalProduct]:
    products: Dict[int, InternalProduct] = {}
    for row in read_csv_rows(path):
        item_id = parse_int(value_of(row, ("itemid", "productid", "goodsid")))
        if item_id is None:
            continue
        brand = value_of(row, ("brand", "brandname"))
        category_name = value_of(row, ("categoryname", "categorycode"))
        price = parse_float(value_of(row, ("price", "unitprice", "amount")))
        products[item_id] = InternalProduct(
            item_id=item_id,
            brand=brand.strip(),
            category_name=category_name.strip(),
            price=price,
        )
    return products


def load_candidates(path: Path) -> List[CandidateProduct]:
    candidates: List[CandidateProduct] = []
    for row in read_csv_rows(path):
        item_id = parse_int(value_of(row, ("itemid", "productid", "goodsid")))
        if item_id is None:
            continue
        source_product_id = value_of(row, ("sourceproductid", "publicproductid")).strip()
        source_url = value_of(row, ("sourceurl", "publicurl")).strip()
        if not source_product_id or not source_url:
            continue
        candidates.append(
            CandidateProduct(
                item_id=item_id,
                source_platform=value_of(row, ("sourceplatform",)).strip().lower() or "jd",
                source_product_id=source_product_id,
                source_url=source_url,
                public_title=value_of(row, ("publictitle", "title")).strip(),
                public_brand=value_of(row, ("publicbrand", "brand")).strip(),
                public_category=value_of(row, ("publiccategory", "category")).strip(),
                public_price=parse_float(value_of(row, ("publicprice", "price"))),
            )
        )
    return candidates


def brand_score(internal_brand: str, public_brand: str, public_title: str) -> float:
    if not internal_brand:
        return 0.0
    internal_exact = internal_brand.strip().lower()
    public_exact = public_brand.strip().lower()
    if public_exact and internal_exact == public_exact:
        return 0.35

    internal_simple = simplify_text(internal_brand)
    public_simple = simplify_text(public_brand)
    title_simple = simplify_text(public_title)
    if public_simple and internal_simple and internal_simple == public_simple:
        return 0.30
    if internal_simple and title_simple and internal_simple in title_simple:
        return 0.25
    return 0.0


def category_keywords(category_name: str) -> Set[str]:
    tokens = tokenize(re.split(r"[>/|]", category_name or "")[-1])
    filtered = {token for token in tokens if token not in GENERIC_CATEGORY_TOKENS}
    if filtered:
        return filtered
    return {token for token in tokenize(category_name) if token not in GENERIC_CATEGORY_TOKENS}


def category_score(internal_category: str, public_category: str) -> float:
    if not internal_category or not public_category:
        return 0.0
    if normalize_text(internal_category) == normalize_text(public_category):
        return 0.20
    if category_keywords(internal_category) & category_keywords(public_category):
        return 0.10
    return 0.0


def price_score(internal_price: Optional[float], public_price: Optional[float]) -> float:
    if internal_price is None or internal_price <= 0:
        return 0.05
    if public_price is None or public_price <= 0:
        return 0.0
    gap_ratio = abs(public_price - internal_price) / internal_price
    if gap_ratio <= 0.10:
        return 0.20
    if gap_ratio <= 0.20:
        return 0.10
    return 0.0


def title_score(internal_brand: str, internal_category: str, public_title: str) -> float:
    if not public_title:
        return 0.0
    title_tokens = tokenize(public_title)
    brand_tokens = tokenize(internal_brand)
    category_tokens = category_keywords(internal_category)
    brand_hit = bool(brand_tokens and brand_tokens <= title_tokens) or bool(brand_tokens & title_tokens)
    category_hit = bool(category_tokens and category_tokens & title_tokens)
    if brand_hit and category_hit:
        return 0.15
    if brand_hit or category_hit:
        return 0.08
    return 0.0


def evidence_score(candidate: CandidateProduct) -> float:
    evidence_count = 0
    if candidate.public_brand:
        evidence_count += 1
    if candidate.public_category:
        evidence_count += 1
    if candidate.public_price is not None and candidate.public_price > 0:
        evidence_count += 1
    if evidence_count >= 2:
        return 0.10
    if evidence_count == 1:
        return 0.05
    return 0.0


def recommend_action(total_score: float) -> str:
    if total_score >= 0.60:
        return "fast_review"
    if total_score >= 0.40:
        return "manual_review"
    return "reject"


def build_reason(parts: Dict[str, float]) -> str:
    labels = {
        "brand_score": "brand",
        "category_score": "category",
        "price_score": "price",
        "title_score": "title",
        "evidence_score": "evidence",
    }
    hits = [labels[key] for key, value in parts.items() if value > 0]
    return ",".join(hits) if hits else "no_match"


def score_candidate(product: InternalProduct, candidate: CandidateProduct) -> Dict[str, object]:
    parts = {
        "brand_score": brand_score(product.brand, candidate.public_brand, candidate.public_title),
        "category_score": category_score(product.category_name, candidate.public_category),
        "price_score": price_score(product.price, candidate.public_price),
        "title_score": title_score(product.brand, product.category_name, candidate.public_title),
        "evidence_score": evidence_score(candidate),
    }
    total_score = round(sum(parts.values()), 2)
    if product.price is None and total_score > 0.85:
        total_score = 0.85

    return {
        "item_id": product.item_id,
        "brand": product.brand,
        "category_name": product.category_name,
        "internal_price": "" if product.price is None else f"{product.price:.2f}",
        "source_platform": candidate.source_platform,
        "source_product_id": candidate.source_product_id,
        "source_url": candidate.source_url,
        "public_title": candidate.public_title,
        "public_brand": candidate.public_brand,
        "public_category": candidate.public_category,
        "public_price": "" if candidate.public_price is None else f"{candidate.public_price:.2f}",
        "brand_score": f"{parts['brand_score']:.2f}",
        "category_score": f"{parts['category_score']:.2f}",
        "price_score": f"{parts['price_score']:.2f}",
        "title_score": f"{parts['title_score']:.2f}",
        "evidence_score": f"{parts['evidence_score']:.2f}",
        "total_score": f"{total_score:.2f}",
        "recommended_action": recommend_action(total_score),
        "score_reason": build_reason(parts),
    }


def score_candidates(products: Dict[int, InternalProduct], candidates: Iterable[CandidateProduct]) -> List[Dict[str, object]]:
    rows: List[Dict[str, object]] = []
    for candidate in candidates:
        product = products.get(candidate.item_id)
        if product is None:
            continue
        rows.append(score_candidate(product, candidate))
    return sorted(
        rows,
        key=lambda row: (
            int(row["item_id"]),
            -float(row["total_score"]),
            str(row["source_product_id"]),
        ),
    )


def write_rows(path: Path, rows: List[Dict[str, object]]) -> None:
    fieldnames = [
        "item_id",
        "brand",
        "category_name",
        "internal_price",
        "source_platform",
        "source_product_id",
        "source_url",
        "public_title",
        "public_brand",
        "public_category",
        "public_price",
        "brand_score",
        "category_score",
        "price_score",
        "title_score",
        "evidence_score",
        "total_score",
        "recommended_action",
        "score_reason",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="商品公网映射候选评分脚本")
    parser.add_argument("--products", required=True, help="内部商品快照 CSV 路径")
    parser.add_argument("--candidates", required=True, help="公网候选商品 CSV 路径")
    parser.add_argument("--output", required=True, help="评分结果 CSV 路径")
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
    products_path = resolve_path(base_dir, args.products)
    candidates_path = resolve_path(base_dir, args.candidates)
    output_path = resolve_path(base_dir, args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    products = load_products(products_path)
    candidates = load_candidates(candidates_path)
    rows = score_candidates(products, candidates)
    write_rows(output_path, rows)

    print(f"已读取 {len(products)} 条内部商品快照。")
    print(f"已读取 {len(candidates)} 条公网候选商品。")
    print(f"已输出 {len(rows)} 条评分结果。")
    print(f"评分文件: {output_path}")
    print("说明：该脚本只生成候选评分与复核建议，不会自动写入正式映射表。")


if __name__ == "__main__":
    main()
