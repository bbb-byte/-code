import argparse
import csv
import json
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

import requests

JD_PLATFORM = "jd"
JD_COMMENT_SUMMARY_URL = "https://club.jd.com/comment/productCommentSummaries.action"
DEFAULT_TIMEOUT = 10
DEFAULT_SLEEP_SECONDS = 0.5


@dataclass
class MappingRow:
    item_id: int
    source_platform: str
    source_product_id: str
    source_url: str
    mapping_confidence: float
    verification_note: str
    evidence_note: str


class JDPublicSatisfactionCrawler:
    def __init__(
        self,
        timeout: int = DEFAULT_TIMEOUT,
        sleep_seconds: float = DEFAULT_SLEEP_SECONDS,
        fixture_dir: Optional[Path] = None,
    ):
        self.timeout = timeout
        self.sleep_seconds = sleep_seconds
        self.fixture_dir = fixture_dir

    def load_mapping_rows(self, path: Path) -> List[MappingRow]:
        rows: List[MappingRow] = []
        with path.open("r", encoding="utf-8-sig", newline="") as handle:
            reader = csv.DictReader(handle)
            for index, raw in enumerate(reader, start=2):
                rows.append(self.validate_mapping_row(raw, index))
        return rows

    def validate_mapping_row(self, raw: Dict[str, str], index: int) -> MappingRow:
        item_id = (raw.get("item_id") or "").strip()
        source_platform = (raw.get("source_platform") or "").strip().lower()
        source_product_id = (raw.get("source_product_id") or "").strip()
        source_url = (raw.get("source_url") or "").strip()
        confidence_text = (raw.get("mapping_confidence") or "1.0").strip()
        verification_note = (raw.get("verification_note") or "").strip()
        evidence_note = (raw.get("evidence_note") or "").strip()

        if not item_id.isdigit():
            raise ValueError(f"第 {index} 行缺少合法 item_id")
        if source_platform != JD_PLATFORM:
            raise ValueError(f"第 {index} 行 source_platform 必须为 {JD_PLATFORM}")
        if not source_url:
            raise ValueError(f"第 {index} 行缺少 source_url")
        if not source_product_id:
            source_product_id = self.extract_product_id(source_url)
        if not source_product_id:
            raise ValueError(f"第 {index} 行缺少 source_product_id，且无法从 source_url 推断")

        mapping_confidence = self.parse_mapping_confidence(confidence_text)
        if mapping_confidence < 0.8 and not verification_note:
            raise ValueError(f"第 {index} 行低置信度映射必须填写 verification_note")

        return MappingRow(
            item_id=int(item_id),
            source_platform=source_platform,
            source_product_id=source_product_id,
            source_url=source_url,
            mapping_confidence=mapping_confidence,
            verification_note=verification_note,
            evidence_note=evidence_note,
        )

    def parse_mapping_confidence(self, value: str) -> float:
        normalized = value.strip().lower()
        label_map = {
            "manual": 0.85,
            "reviewed": 0.95,
            "verified": 1.0,
        }
        if normalized in label_map:
            return label_map[normalized]
        return float(normalized)

    def extract_product_id(self, source_url: str) -> str:
        match = re.search(r"/(\d+)\.html", source_url)
        return match.group(1) if match else ""

    def parse_summary_payload(self, text: str) -> List[Dict[str, object]]:
        payload = json.loads(text)
        if isinstance(payload, list):
            return payload
        summaries = payload.get("CommentsCount") or payload.get("productCommentSummaries") or payload.get("productCommentSummary") or []
        if isinstance(summaries, list):
            return summaries
        return [summaries]

    def fetch_comment_summary(self, row: MappingRow) -> Dict[str, object]:
        if self.fixture_dir:
            fixture_path = self.fixture_dir / f"{row.source_product_id}.json"
            if fixture_path.exists():
                return self.parse_summary_payload(fixture_path.read_text(encoding="utf-8"))[0]

            sample_json = self.fixture_dir / "jd_comment_summary.sample.json"
            if sample_json.exists():
                return self.parse_summary_payload(sample_json.read_text(encoding="utf-8"))[0]

        response = requests.get(
            JD_COMMENT_SUMMARY_URL,
            params={"referenceIds": row.source_product_id},
            headers={"User-Agent": "Mozilla/5.0", "Referer": row.source_url},
            timeout=self.timeout,
        )
        response.raise_for_status()
        payload = self.parse_jsonp(response.text)
        summaries = payload.get("CommentsCount") or payload.get("productCommentSummaries") or payload.get("productCommentSummary") or []
        if isinstance(summaries, list) and summaries:
            return summaries[0]
        return {}

    def fetch_product_page(self, row: MappingRow) -> str:
        if self.fixture_dir:
            html_path = self.fixture_dir / f"{row.source_product_id}.html"
            if html_path.exists():
                return html_path.read_text(encoding="utf-8")

            sample_html = self.fixture_dir / "jd_product_page.sample.html"
            if sample_html.exists():
                return sample_html.read_text(encoding="utf-8")

        response = requests.get(
            row.source_url,
            headers={"User-Agent": "Mozilla/5.0"},
            timeout=self.timeout,
        )
        response.raise_for_status()
        return response.text

    def parse_jsonp(self, text: str) -> Dict[str, object]:
        raw = text.strip()
        if raw.startswith("{"):
            return json.loads(raw)
        match = re.search(r"\((.*)\)\s*;?\s*$", raw)
        if not match:
            raise ValueError("无法解析京东评价摘要响应")
        return json.loads(match.group(1))

    def extract_metrics(self, summary: Dict[str, object], html: str) -> Dict[str, object]:
        positive_rate = self.extract_positive_rate(summary, html)
        review_count = self.extract_review_count(summary)
        shop_score = self.extract_shop_score(summary, html)
        rating_text = self.extract_rating_text(summary, html)
        return {
            "positive_rate": positive_rate,
            "review_count": review_count,
            "shop_score": shop_score,
            "rating_text": rating_text,
        }

    def extract_positive_rate(self, summary: Dict[str, object], html: str) -> Optional[float]:
        value = summary.get("goodRate")
        if value is None:
            value = summary.get("goodRateShow")
        numeric = self.parse_numeric(value)
        if numeric is not None:
            return numeric / 100 if numeric > 1 else numeric

        match = re.search(r"好评率[：:\s]*([0-9]+(?:\.[0-9]+)?)%", html)
        if match:
            return float(match.group(1)) / 100
        return None

    def extract_review_count(self, summary: Dict[str, object]) -> Optional[int]:
        value = summary.get("commentCount")
        if value is not None:
            return int(value)
        value = summary.get("commentCountStr")
        if value is not None:
            return self.parse_count_string(str(value))
        return None

    def extract_shop_score(self, summary: Dict[str, object], html: str) -> Optional[float]:
        value = summary.get("score") or summary.get("averageScore")
        numeric = self.parse_numeric(value)
        if numeric is not None:
            return numeric
        match = re.search(r"店铺评分[：:\s]*([0-9]+(?:\.[0-9]+)?)", html)
        if match:
            return float(match.group(1))
        return None

    def extract_rating_text(self, summary: Dict[str, object], html: str) -> Optional[str]:
        score_remark = summary.get("scoreRemark")
        if score_remark:
            return str(score_remark)
        match = re.search(r"(好评率[：:\s]*[0-9]+(?:\.[0-9]+)?%)", html)
        if match:
            return match.group(1).replace("：", " ").replace(":", " ").strip()
        return None

    def parse_numeric(self, value: object) -> Optional[float]:
        if value is None:
            return None
        if isinstance(value, (int, float)):
            return float(value)
        try:
            return float(str(value).strip().replace("%", ""))
        except ValueError:
            return None

    def parse_count_string(self, value: str) -> Optional[int]:
        normalized = value.strip().replace(",", "").replace("+", "")
        if "万" in normalized:
            return int(float(normalized.replace("万", "")) * 10000)
        try:
            return int(float(normalized))
        except ValueError:
            return None

    def normalize_row(self, row: MappingRow, metrics: Dict[str, object], raw_summary: Dict[str, object], crawl_status: str) -> Dict[str, object]:
        return {
            "item_id": row.item_id,
            "source_platform": row.source_platform,
            "source_product_id": row.source_product_id,
            "source_url": row.source_url,
            "positive_rate": metrics.get("positive_rate"),
            "review_count": metrics.get("review_count"),
            "shop_score": metrics.get("shop_score"),
            "rating_text": metrics.get("rating_text"),
            "crawl_status": crawl_status,
            "raw_payload": json.dumps(raw_summary, ensure_ascii=False),
            "crawled_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        }

    def crawl(self, mapping_path: Path) -> List[Dict[str, object]]:
        results: List[Dict[str, object]] = []
        for row in self.load_mapping_rows(mapping_path):
            try:
                summary = self.fetch_comment_summary(row)
                html = self.fetch_product_page(row)
                metrics = self.extract_metrics(summary, html)
                crawl_status = "success" if any(metrics.values()) else "empty"
                results.append(self.normalize_row(row, metrics, summary, crawl_status))
            except Exception as exc:
                results.append({
                    "item_id": row.item_id,
                    "source_platform": row.source_platform,
                    "source_product_id": row.source_product_id,
                    "source_url": row.source_url,
                    "positive_rate": None,
                    "review_count": None,
                    "shop_score": None,
                    "rating_text": None,
                    "crawl_status": "failed",
                    "raw_payload": json.dumps({"error": str(exc)}, ensure_ascii=False),
                    "crawled_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
                })
            time.sleep(self.sleep_seconds)
        return results


def write_csv(path: Path, rows: List[Dict[str, object]]) -> None:
    fieldnames = [
        "item_id",
        "source_platform",
        "source_product_id",
        "source_url",
        "positive_rate",
        "review_count",
        "shop_score",
        "rating_text",
        "crawl_status",
        "raw_payload",
        "crawled_at",
    ]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_json(path: Path, rows: List[Dict[str, object]]) -> None:
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="京东商品公网满意度指标采集脚本")
    parser.add_argument("--mapping", default="mappings/product_public_mapping.jd.sample.csv", help="映射 CSV 路径")
    parser.add_argument("--output-dir", default="output", help="输出目录")
    parser.add_argument("--fixture-dir", default="", help="本地夹具目录，用于离线验证解析逻辑")
    parser.add_argument("--timeout", default=DEFAULT_TIMEOUT, type=int, help="HTTP 超时时间(秒)")
    parser.add_argument("--sleep-seconds", default=DEFAULT_SLEEP_SECONDS, type=float, help="每次请求之间的等待时间")
    return parser


def resolve_path(base_dir: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    if path.exists():
        return path.resolve()
    return (base_dir / path).resolve()


def main() -> None:
    args = build_parser().parse_args()
    base_dir = Path(__file__).resolve().parent
    mapping_path = resolve_path(base_dir, args.mapping)
    output_dir = resolve_path(base_dir, args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    fixture_dir = resolve_path(base_dir, args.fixture_dir) if args.fixture_dir else None

    crawler = JDPublicSatisfactionCrawler(
        timeout=args.timeout,
        sleep_seconds=args.sleep_seconds,
        fixture_dir=fixture_dir,
    )
    rows = crawler.crawl(mapping_path)

    csv_path = output_dir / "jd_product_public_metrics.csv"
    json_path = output_dir / "jd_product_public_metrics.json"
    write_csv(csv_path, rows)
    write_json(json_path, rows)

    print(f"共采集 {len(rows)} 条商品公网满意度指标记录。")
    print(f"CSV 输出: {csv_path}")
    print(f"JSON 输出: {json_path}")
    print("说明：该脚本只采集京东商品公开评价摘要，不生成用户级 buy/fav/cart/pv 行为。")


if __name__ == "__main__":
    main()
