import argparse
import csv
import re
import sys
import time
import random
import logging
from dataclasses import dataclass
from html import unescape
from pathlib import Path
from typing import Dict, Iterable, List, Optional
from urllib.parse import urljoin

from DrissionPage import Chromium, ChromiumOptions

from mapping_scorer import load_products

logger = logging.getLogger(__name__)

JD_SEARCH_URL = "https://search.jd.com/Search"
DEFAULT_TIMEOUT = 10
DEFAULT_TOP_K = 5
DEFAULT_SLEEP_SECONDS = 5.0
DEFAULT_SLEEP_JITTER = (1.0, 3.0)


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
        allow_sample_fallback: bool = False,
        request_delay: float = DEFAULT_SLEEP_SECONDS,
        browser_path: str = "",
        headless: bool = False,
    ):
        self.timeout = timeout
        self.fixture_dir = fixture_dir
        self.allow_sample_fallback = allow_sample_fallback
        self.request_delay = max(0.0, request_delay)
        self.shared_sample_hits = 0
        self.risk_page_hits = 0
        self.debug_dir: Optional[Path] = None
        self.browser: Optional[Chromium] = None
        self.browser_path = browser_path
        self.headless = headless

    def _init_browser(self) -> None:
        """初始化 Chrome 浏览器。"""
        if self.fixture_dir:
            return
        co = ChromiumOptions()
        # 强制使用 Chrome
        browser = self.browser_path or r"C:\Program Files\Google\Chrome\Application\chrome.exe"
        co.set_browser_path(browser)
        # 独立调试端口和用户数据目录，避免冲突
        co.set_local_port(19222)
        co.set_user_data_path(str(Path(__file__).resolve().parent / ".chrome_profile"))
        if self.headless:
            co.headless()
        self.browser = Chromium(co)
        logger.info("Chrome 浏览器已初始化 (%s)", browser)

    def _close_browser(self) -> None:
        if self.browser:
            try:
                self.browser.quit()
            except Exception:
                pass

    def _random_sleep(self) -> None:
        """随机等待，避免固定间隔被检测。"""
        jitter = random.uniform(*DEFAULT_SLEEP_JITTER)
        duration = self.request_delay + jitter
        logger.debug("睡眠 %.1f 秒", duration)
        time.sleep(duration)

    def set_debug_dir(self, debug_dir: Optional[Path]) -> None:
        self.debug_dir = debug_dir
        if self.debug_dir:
            self.debug_dir.mkdir(parents=True, exist_ok=True)

    def build_keyword(self, brand: str, category_name: str) -> str:
        brand_part = (brand or "").strip()
        category_part = self.extract_category_keyword(category_name)
        parts = [part for part in (brand_part, category_part) if part]
        return " ".join(parts) or category_name.strip() or brand_part

    def extract_category_keyword(self, category_name: str) -> str:
        if not category_name:
            return ""
        parts = [segment.strip() for segment in re.split(r"[>/|.]", category_name) if segment.strip()]
        if not parts:
            return ""
        return parts[-1]

    def fetch_search_page(self, item_id: int, keyword: str) -> str:
        if self.fixture_dir:
            item_fixture = self.fixture_dir / f"jd_search_{item_id}.html"
            if item_fixture.exists():
                return item_fixture.read_text(encoding="utf-8")

            sample_fixture = self.fixture_dir / "jd_search_result.sample.html"
            if self.allow_sample_fallback and sample_fixture.exists():
                self.shared_sample_hits += 1
                return sample_fixture.read_text(encoding="utf-8")

        # 使用真实浏览器访问搜索页
        tab = self.browser.latest_tab
        search_url = f"{JD_SEARCH_URL}?keyword={keyword}"
        tab.get(search_url)
        tab.wait(3)

        # 等待搜索结果加载
        try:
            tab.wait.eles_loaded("#J_goodsList .gl-item", timeout=self.timeout)
        except Exception:
            logger.warning("搜索结果未完全加载: %s", keyword)

        html = tab.html
        return html

    def save_debug_html(self, item_id: int, keyword: str, html: str) -> Optional[Path]:
        if not self.debug_dir:
            return None
        safe_keyword = re.sub(r"[^0-9a-zA-Z\u4e00-\u9fff_-]+", "_", keyword).strip("_") or "empty"
        debug_path = self.debug_dir / f"jd_search_debug_{item_id}_{safe_keyword[:40]}.html"
        debug_path.write_text(html, encoding="utf-8")
        return debug_path

    def is_risk_page(self, html: str) -> bool:
        lowered = (html or "").lower()
        return (
            "jdr_shields" in lowered
            or "risk_handler" in lowered
            or "privatedomain/risk_handler" in lowered
            or "访问频繁" in lowered
            or "请稍后再试" in lowered
        )

    def parse_search_results(
        self,
        html: str,
        brand_hint: str = "",
        category_hint: str = "",
        top_k: int = DEFAULT_TOP_K,
    ) -> List[SearchCandidate]:
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
            source_url = self.extract_first(
                body,
                r'<a[^>]+href="(?P<url>//item\.jd\.com/\d+\.html|https?://item\.jd\.com/\d+\.html)"',
            )
            if not source_url:
                source_url = f"https://item.jd.com/{sku}.html"
            elif source_url.startswith("//"):
                source_url = urljoin("https:", source_url)
            title = self.clean_html(
                self.extract_first(
                    body,
                    r'<div[^>]*class="[^"]*p-name[^"]*"[^>]*>.*?<em[^>]*>(?P<value>.*?)</em>',
                )
            )
            public_price = self.parse_price(
                self.extract_first(
                    body,
                    r'<div[^>]*class="[^"]*p-price[^"]*"[^>]*>.*?<i[^>]*>(?P<value>.*?)</i>',
                )
            )
            shop_text = self.clean_html(
                self.extract_first(
                    body,
                    r'<div[^>]*class="[^"]*p-shop[^"]*"[^>]*>.*?<a[^>]*>(?P<value>.*?)</a>',
                )
            )
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
    parser = argparse.ArgumentParser(description="Recall JD product candidates for internal products")
    parser.add_argument("--products", required=True, help="Internal product snapshot CSV path")
    parser.add_argument("--output", required=True, help="Candidate CSV output path")
    parser.add_argument("--fixture-dir", default="", help="Optional fixture directory for offline parsing")
    parser.add_argument(
        "--allow-sample-fallback",
        action="store_true",
        help="Allow shared sample search result fallback for demo runs only",
    )
    parser.add_argument("--top-k", default=DEFAULT_TOP_K, type=int, help="Max candidates kept for each product")
    parser.add_argument("--max-products", default=0, type=int, help="Max number of products to recall; 0 means all")
    parser.add_argument("--timeout", default=DEFAULT_TIMEOUT, type=int, help="HTTP timeout in seconds")
    parser.add_argument("--browser-path", default="", help="浏览器可执行文件路径")
    parser.add_argument("--headless", action="store_true", help="无头模式运行")
    return parser


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    args = build_parser().parse_args()
    base_dir = Path(__file__).resolve().parent
    products_path = resolve_path(base_dir, args.products)
    output_path = resolve_path(base_dir, args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    fixture_dir = resolve_path(base_dir, args.fixture_dir) if args.fixture_dir else None
    debug_dir = output_path.parent / "debug"

    products = load_products(products_path)
    product_list = list(products.values())
    if args.max_products and args.max_products > 0:
        product_list = product_list[:args.max_products]
    recall = JDCandidateRecall(
        timeout=args.timeout,
        fixture_dir=fixture_dir,
        allow_sample_fallback=args.allow_sample_fallback,
        browser_path=args.browser_path,
        headless=args.headless,
    )
    recall.set_debug_dir(debug_dir)
    recall._init_browser()

    try:
        rows: List[Dict[str, object]] = []
        debug_files: List[Path] = []
        total = len(product_list)
        for idx, product in enumerate(product_list, start=1):
            keyword = recall.build_keyword(product.brand, product.category_name)
            logger.info("搜索进度 %d/%d — keyword='%s'", idx, total, keyword)
            html = recall.fetch_search_page(product.item_id, keyword)
            if recall.is_risk_page(html):
                recall.risk_page_hits += 1
                logger.warning("商品 %s 搜索被风控拦截", product.item_id)
            candidates = recall.parse_search_results(
                html=html,
                brand_hint=product.brand,
                category_hint=product.category_name,
                top_k=args.top_k,
            )
            if not candidates:
                debug_path = recall.save_debug_html(product.item_id, keyword, html)
                if debug_path:
                    debug_files.append(debug_path)
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
            recall._random_sleep()

        write_rows(output_path, rows)
        print(f"Loaded {len(products)} internal products.")
        if args.max_products and args.max_products > 0:
            print(f"Processed first {len(product_list)} products due to max-products limit.")
        print(f"Wrote {len(rows)} candidate rows.")
        if recall.shared_sample_hits:
            print(
                f"Notice: shared sample search page reused for {recall.shared_sample_hits} items; results are demo-only."
            )
        if recall.risk_page_hits:
            print(f"Risk-handler pages detected for {recall.risk_page_hits} products.")
        if debug_files:
            print(f"Saved {len(debug_files)} debug search pages to {debug_dir}")
        print(f"Candidate file: {output_path}")
        print("This script only generates candidate rows. Final mapping still requires scoring and human review.")
        if not rows and recall.risk_page_hits:
            print("Recall failed because live requests were blocked by the target site's risk-control page.")
            raise SystemExit(2)
    finally:
        recall._close_browser()


if __name__ == "__main__":
    main()
