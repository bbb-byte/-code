import argparse
import concurrent.futures
import csv
import re
import threading
import time
from dataclasses import dataclass
from html import unescape
from pathlib import Path
from typing import Dict, Iterable, List, Optional
from urllib.parse import quote_plus, urljoin

import requests

from mapping_scorer import load_products


JD_SEARCH_URL = "https://search.jd.com/Search"
DEFAULT_TIMEOUT = 10
DEFAULT_TOP_K = 5
DEFAULT_MAX_WORKERS = 4
DEFAULT_REQUEST_DELAY = 0.4
DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Cache-Control": "no-cache",
    "Pragma": "no-cache",
}


@dataclass
class SearchCandidate:
    source_product_id: str
    source_url: str
    public_title: str
    public_brand: str
    public_category: str
    public_price: Optional[float]
    rank: int


class JDCandidateRecall:
    def __init__(
        self,
        timeout: int = DEFAULT_TIMEOUT,
        fixture_dir: Optional[Path] = None,
        max_workers: int = DEFAULT_MAX_WORKERS,
        request_delay: float = DEFAULT_REQUEST_DELAY,
    ):
        self.timeout = timeout
        self.fixture_dir = fixture_dir
        self.max_workers = max(1, max_workers)
        self.request_delay = max(0.0, request_delay)
        self._request_lock = threading.Lock()
        self._next_request_at = 0.0
        self._thread_local = threading.local()

    def build_keyword(self, brand: str, category_name: str) -> str:
        brand_part = (brand or "").strip()
        category_part = self.extract_category_keyword(category_name)
        parts = [part for part in (brand_part, category_part) if part]
        return " ".join(parts) or category_name.strip() or brand_part

    def extract_category_keyword(self, category_name: str) -> str:
        if not category_name:
            return ""
        parts = [segment.strip() for segment in re.split(r"[.>/|]", category_name) if segment.strip()]
        if not parts:
            return ""
        return parts[-1]

    def fetch_search_page(self, item_id: int, keyword: str) -> str:
        if self.fixture_dir:
            item_fixture = self.fixture_dir / f"jd_search_{item_id}.html"
            if item_fixture.exists():
                return item_fixture.read_text(encoding="utf-8")

            sample_fixture = self.fixture_dir / "jd_search_result.sample.html"
            if sample_fixture.exists():
                return sample_fixture.read_text(encoding="utf-8")

        self.wait_for_request_slot()
        response = self.get_session().get(
            JD_SEARCH_URL,
            params={"keyword": keyword},
            headers={"Referer": f"{JD_SEARCH_URL}?keyword={quote_plus(keyword)}"},
            timeout=self.timeout,
        )
        response.raise_for_status()
        return response.text

    def get_session(self) -> requests.Session:
        session = getattr(self._thread_local, "session", None)
        if session is None:
            session = requests.Session()
            session.headers.update(DEFAULT_HEADERS)
            self._thread_local.session = session
        return session

    def wait_for_request_slot(self) -> None:
        if self.request_delay <= 0:
            return
        sleep_seconds = 0.0
        with self._request_lock:
            now = time.monotonic()
            if now < self._next_request_at:
                sleep_seconds = self._next_request_at - now
                self._next_request_at += self.request_delay
            else:
                self._next_request_at = now + self.request_delay
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)

    def parse_search_results(self, html: str, brand_hint: str = "", category_hint: str = "", top_k: int = DEFAULT_TOP_K) -> List[SearchCandidate]:
        candidates: List[SearchCandidate] = []
        pattern = re.compile(
            r'<li[^>]*class="[^"]*gl-item[^"]*"[^>]*data-sku="(?P<sku>\d+)"[^>]*>(?P<body>.*?)</li>',
            re.S,
        )
        for rank, match in enumerate(pattern.finditer(html), start=1):
            if rank > top_k:
                break
            body = match.group("body")
            sku = match.group("sku")
            source_url = self.extract_first(body, r'<a[^>]+href="(?P<url>//item\.jd\.com/\d+\.html|https?://item\.jd\.com/\d+\.html)"')
            if not source_url:
                source_url = f"https://item.jd.com/{sku}.html"
            elif source_url.startswith("//"):
                source_url = urljoin("https:", source_url)
            title = self.clean_html(self.extract_first(body, r'<div[^>]*class="[^"]*p-name[^"]*"[^>]*>.*?<em[^>]*>(?P<value>.*?)</em>'))
            public_price = self.parse_price(self.extract_first(body, r'<div[^>]*class="[^"]*p-price[^"]*"[^>]*>.*?<i[^>]*>(?P<value>.*?)</i>'))
            shop_text = self.clean_html(self.extract_first(body, r'<div[^>]*class="[^"]*p-shop[^"]*"[^>]*>.*?<a[^>]*>(?P<value>.*?)</a>'))
            public_brand = self.infer_brand(title, brand_hint)
            public_category = self.infer_category(title, category_hint)
            if not title:
                continue
            candidates.append(
                SearchCandidate(
                    source_product_id=sku,
                    source_url=source_url,
                    public_title=title,
                    public_brand=public_brand or shop_text,
                    public_category=public_category,
                    public_price=public_price,
                    rank=rank,
                )
            )
        return candidates

    def infer_brand(self, title: str, brand_hint: str) -> str:
        if not brand_hint:
            return ""
        title_simple = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", title.lower())
        brand_simple = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", brand_hint.lower())
        if brand_simple and brand_simple in title_simple:
            return brand_hint
        return ""

    def infer_category(self, title: str, category_hint: str) -> str:
        if not category_hint:
            return ""
        keyword = self.extract_category_keyword(category_hint)
        if not keyword:
            return ""
        title_tokens = re.sub(r"[^0-9a-z\u4e00-\u9fff]+", " ", title.lower()).split()
        if keyword.lower() in title_tokens:
            return category_hint
        return ""

    def parse_price(self, raw: str) -> Optional[float]:
        if not raw:
            return None
        try:
            return float(raw.replace(",", "").strip())
        except ValueError:
            return None

    def clean_html(self, raw: str) -> str:
        if not raw:
            return ""
        without_tags = re.sub(r"<[^>]+>", " ", raw)
        normalized = re.sub(r"\s+", " ", unescape(without_tags)).strip()
        return normalized

    def extract_first(self, text: str, pattern: str) -> str:
        match = re.search(pattern, text, re.S)
        if not match:
            return ""
        return match.group("value") if "value" in match.groupdict() else match.group("url")


def write_rows(path: Path, rows: Iterable[Dict[str, object]]) -> None:
    fieldnames = [
        "item_id",
        "source_platform",
        "source_product_id",
        "source_url",
        "public_title",
        "public_brand",
        "public_category",
        "public_price",
        "search_keyword",
        "rank",
        "candidate_source",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="京东商品候选召回脚本")
    parser.add_argument("--products", required=True, help="内部商品快照 CSV 路径")
    parser.add_argument("--output", required=True, help="候选结果 CSV 路径")
    parser.add_argument("--fixture-dir", default="", help="本地夹具目录，用于离线验证解析逻辑")
    parser.add_argument("--top-k", default=DEFAULT_TOP_K, type=int, help="每个商品保留多少候选结果")
    parser.add_argument("--max-workers", default=DEFAULT_MAX_WORKERS, type=int, help="并发召回线程数")
    parser.add_argument("--request-delay", default=DEFAULT_REQUEST_DELAY, type=float, help="全局相邻请求最小间隔(秒)")
    parser.add_argument("--timeout", default=DEFAULT_TIMEOUT, type=int, help="HTTP 超时时间(秒)")
    return parser


def recall_one_product(recall: JDCandidateRecall, product, top_k: int) -> Dict[str, object]:
    keyword = recall.build_keyword(product.brand, product.category_name)
    html = recall.fetch_search_page(product.item_id, keyword)
    candidates = recall.parse_search_results(
        html=html,
        brand_hint=product.brand,
        category_hint=product.category_name,
        top_k=top_k,
    )
    rows = []
    for candidate in candidates:
        rows.append(
            {
                "item_id": product.item_id,
                "source_platform": "jd",
                "source_product_id": candidate.source_product_id,
                "source_url": candidate.source_url,
                "public_title": candidate.public_title,
                "public_brand": candidate.public_brand,
                "public_category": candidate.public_category,
                "public_price": "" if candidate.public_price is None else f"{candidate.public_price:.2f}",
                "search_keyword": keyword,
                "rank": candidate.rank,
                "candidate_source": "jd_search",
            }
        )
    return {"item_id": product.item_id, "keyword": keyword, "rows": rows}


def main() -> None:
    args = build_parser().parse_args()
    base_dir = Path(__file__).resolve().parent
    products_path = resolve_path(base_dir, args.products)
    output_path = resolve_path(base_dir, args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_dir = resolve_path(base_dir, args.fixture_dir) if args.fixture_dir else None

    products = load_products(products_path)
    recall = JDCandidateRecall(
        timeout=args.timeout,
        fixture_dir=fixture_dir,
        max_workers=args.max_workers,
        request_delay=args.request_delay,
    )
    product_list = list(products.values())
    total_products = len(product_list)

    indexed_rows: List[Optional[List[Dict[str, object]]]] = [None] * total_products
    if recall.max_workers == 1 or total_products <= 1:
        for index, product in enumerate(product_list, start=1):
            result = recall_one_product(recall, product, args.top_k)
            print(f"PROGRESS\t{index}\t{total_products}\t{result['item_id']}\t{result['keyword']}", flush=True)
            indexed_rows[index - 1] = result["rows"]
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(recall.max_workers, total_products)) as executor:
            future_map = {
                executor.submit(recall_one_product, recall, product, args.top_k): index
                for index, product in enumerate(product_list, start=1)
            }
            completed = 0
            for future in concurrent.futures.as_completed(future_map):
                index = future_map[future]
                result = future.result()
                completed += 1
                print(f"PROGRESS\t{completed}\t{total_products}\t{result['item_id']}\t{result['keyword']}", flush=True)
                indexed_rows[index - 1] = result["rows"]

    rows: List[Dict[str, object]] = []
    for batch in indexed_rows:
        if batch:
            rows.extend(batch)

    write_rows(output_path, rows)
    print(f"已读取 {len(products)} 条内部商品快照。")
    print(f"已输出 {len(rows)} 条候选商品。")
    print(f"候选文件: {output_path}")
    print("说明：该脚本只生成候选商品列表，正式映射仍需评分与人工复核。")


if __name__ == "__main__":
    main()
