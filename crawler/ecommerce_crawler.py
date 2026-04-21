import argparse
import csv
import json
import logging
import os
import random
import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

from DrissionPage import Chromium, ChromiumOptions

logger = logging.getLogger(__name__)

JD_PLATFORM = "jd"
JD_COMMENT_SUMMARY_URL = "club.jd.com/comment/productCommentSummaries.action"
DEFAULT_TIMEOUT = 15
DEFAULT_SLEEP_SECONDS = 5.0
DEFAULT_MAX_RETRIES = 3
DEFAULT_RETRY_BACKOFF_SECONDS = 10.0
DEFAULT_SLEEP_JITTER = (1.0, 3.0)
CONSECUTIVE_BLOCK_THRESHOLD = 3
CONSECUTIVE_BLOCK_PAUSE_SECONDS = 120
DEFAULT_CDP_PORT = 9222
DEFAULT_FALLBACK_CDP_PORT = 19222

# 评论区 CSS 选择器 (京东商品页)
COMMENT_TAB_SELECTOR = "#detail .tab-main-item:nth-child(5)"
COMMENT_AREA_SELECTORS = [
    ".percent-con",
    ".comment-percent",
    ".J-comm-score",
    "#comment-0",
    ".comment-column",
]


@dataclass
class MappingRow:
    item_id: int
    source_platform: str
    source_product_id: str
    source_url: str
    mapping_confidence: float
    verification_note: str
    evidence_note: str


class TransientCrawlError(RuntimeError):
    pass


class JDPublicSatisfactionCrawler:
    """使用 DrissionPage 操控真实浏览器采集京东评价数据。

    核心原理：
    1. 接管用户已登录的 Chrome/Edge 浏览器（避免登录问题）
    2. 访问商品页并滚动到评论区（触发 JS 异步加载）
    3. 监听 club.jd.com 评论 API 的网络响应包来提取数据
    4. 如果网络监听没拿到，则尝试从 DOM 解析
    """

    def __init__(
        self,
        timeout: int = DEFAULT_TIMEOUT,
        sleep_seconds: float = DEFAULT_SLEEP_SECONDS,
        max_retries: int = DEFAULT_MAX_RETRIES,
        retry_backoff_seconds: float = DEFAULT_RETRY_BACKOFF_SECONDS,
        fixture_dir: Optional[Path] = None,
        headless: bool = False,
        browser_path: str = "",
        browser_user_data_dir: Optional[Path] = None,
    ):
        self.timeout = timeout
        self.sleep_seconds = sleep_seconds
        self.max_retries = max(0, max_retries)
        self.retry_backoff_seconds = max(0.0, retry_backoff_seconds)
        self.fixture_dir = fixture_dir
        self._consecutive_blocks = 0
        self.headless = headless
        self.browser: Optional[Chromium] = None
        self.browser_path = browser_path
        self.browser_user_data_dir = browser_user_data_dir
        self._owns_browser = False

    # ------------------------------------------------------------------
    # Browser management
    # ------------------------------------------------------------------

    def _init_browser(self) -> None:
        """初始化浏览器：复用已登录京东的调试 Chrome。
        
        1. 优先连接已运行的 Chrome（CDP 端口 9222）
        2. 如果没有，自动启动带调试端口的 Chrome（使用 Debug Profile 保存登录态）
        """
        if self.fixture_dir:
            return

        debug_profile_dir = self.browser_user_data_dir or (Path(__file__).resolve().parent / "output" / "browser-profile")
        debug_profile_dir.mkdir(parents=True, exist_ok=True)

        # 第一步：尝试连接已有的调试 Chrome
        try:
            co = ChromiumOptions()
            co.set_local_port(DEFAULT_CDP_PORT)
            self.browser = Chromium(co)
            self._owns_browser = False
            logger.info("✓ 已接管现有 Chrome 浏览器（端口 9222）")
            return
        except Exception:
            logger.info("未检测到已运行的调试 Chrome，正在自动启动...")

        # 第二步：自动启动带调试端口的 Chrome
        try:
            co = ChromiumOptions()
            if self.browser_path:
                co.set_browser_path(self.browser_path)
            co.set_local_port(DEFAULT_CDP_PORT)
            co.set_user_data_path(str(debug_profile_dir))
            if self.headless:
                co.headless()
            self.browser = Chromium(co)
            self._owns_browser = True
            logger.info("✓ 已自动启动 Chrome（端口 9222，Debug Profile）")
            logger.info("  首次使用请在浏览器中登录京东")
            return
        except Exception as e:
            logger.warning("自动启动 Chrome 失败: %s，回退到独立模式", e)

        # 回退：独立 Chrome
        co = ChromiumOptions()
        if self.browser_path:
            co.set_browser_path(self.browser_path)
        co.set_local_port(DEFAULT_FALLBACK_CDP_PORT)
        co.set_user_data_path(str(debug_profile_dir))
        if self.headless:
            co.headless()
        self.browser = Chromium(co)
        logger.info("Chrome 浏览器已独立启动（端口 19222）")

    def _close_browser(self) -> None:
        if self.browser:
            try:
                if self._owns_browser:
                    self.browser.quit()
                else:
                    logger.info("保持用户的 Chrome 浏览器不关闭")
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Anti-detection helpers
    # ------------------------------------------------------------------

    def _random_sleep(self) -> None:
        """在基础等待时间上叠加随机抖动，避免固定间隔被检测。"""
        jitter = random.uniform(*DEFAULT_SLEEP_JITTER)
        duration = self.sleep_seconds + jitter
        logger.debug("睡眠 %.1f 秒", duration)
        time.sleep(duration)

    def _on_block_detected(self) -> None:
        """记录连续拦截次数，达到阈值时自动暂停。"""
        self._consecutive_blocks += 1
        if self._consecutive_blocks >= CONSECUTIVE_BLOCK_THRESHOLD:
            logger.warning(
                "连续被拦截 %d 次，自动暂停 %d 秒",
                self._consecutive_blocks,
                CONSECUTIVE_BLOCK_PAUSE_SECONDS,
            )
            time.sleep(CONSECUTIVE_BLOCK_PAUSE_SECONDS)
            self._consecutive_blocks = 0

    def _on_success(self) -> None:
        """成功采集后重置连续拦截计数。"""
        self._consecutive_blocks = 0

    # ------------------------------------------------------------------
    # CSV loading & validation
    # ------------------------------------------------------------------

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

    # ------------------------------------------------------------------
    # Data fetching via real browser
    # ------------------------------------------------------------------

    def parse_summary_payload(self, text: str) -> List[Dict[str, object]]:
        payload = json.loads(text)
        if isinstance(payload, list):
            return payload
        summaries = payload.get("CommentsCount") or payload.get("productCommentSummaries") or payload.get("productCommentSummary") or []
        if isinstance(summaries, list):
            return summaries
        return [summaries]

    def _parse_jsonp(self, text: str) -> Dict[str, object]:
        """解析 JSONP 或 JSON 响应。"""
        raw = text.strip()
        if raw.startswith("{"):
            return json.loads(raw)
        match = re.search(r"\((.*)\)\s*;?\s*$", raw)
        if match:
            return json.loads(match.group(1))
        return json.loads(raw)

    def fetch_comment_summary_via_browser(self, row: MappingRow) -> Dict[str, object]:
        """通过真实浏览器访问商品页，提取评价数据。

        策略：
        1. 监听多种评论 API（旧版 club.jd.com + 新版 api.m.jd.com）
        2. 同时滚动页面、点击"大家评"标签触发数据加载
        3. 如果 API 没捕到，从 DOM 直接解析
        """
        if self.fixture_dir:
            fixture_path = self.fixture_dir / f"{row.source_product_id}.json"
            if fixture_path.exists():
                return self.parse_summary_payload(fixture_path.read_text(encoding="utf-8"))[0]
            sample_json = self.fixture_dir / "jd_comment_summary.sample.json"
            if sample_json.exists():
                return self.parse_summary_payload(sample_json.read_text(encoding="utf-8"))[0]

        tab = self.browser.latest_tab

        # 同时监听新旧两种评论 API
        tab.listen.start([JD_COMMENT_SUMMARY_URL, "api.m.jd.com"])

        # 访问商品页
        tab.get(row.source_url)
        tab.wait(3)

        # 滚动页面触发异步加载
        tab.run_js("window.scrollBy(0, 600)")
        tab.wait(1)
        # 尝试点击"大家评"标签页
        try:
            comment_tab = (
                tab.ele('text:大家评', timeout=3)
                or tab.ele('text:商品评价', timeout=2)
                or tab.ele('text:评价', timeout=2)
            )
            if comment_tab:
                comment_tab.click()
                tab.wait(2)
                logger.debug("已点击评论标签")
        except Exception:
            pass

        # 旧方式：尝试滚动到 #comment
        tab.run_js("document.getElementById('comment') && document.getElementById('comment').scrollIntoView()")
        tab.wait(1)

        # 捕获 API 响应（短超时，因为新版页面可能不走这些 API）
        summary = {}
        try:
            packet = tab.listen.wait(timeout=5)
            if packet and packet.response and packet.response.body:
                body = packet.response.body
                if isinstance(body, bytes):
                    body = body.decode("utf-8", errors="replace")
                try:
                    payload = self._parse_jsonp(body)
                    # 旧版 API
                    summaries = payload.get("CommentsCount") or payload.get("productCommentSummaries") or payload.get("productCommentSummary") or []
                    if isinstance(summaries, list) and summaries:
                        summary = summaries[0]
                        logger.info("通过网络监听获取到评论摘要 (旧版API)")
                    # 新版 API
                    elif "commentInfo" in payload:
                        comment_info = payload["commentInfo"]
                        summary = {
                            "goodRate": comment_info.get("goodRate"),
                            "commentCount": comment_info.get("commentCount"),
                        }
                        logger.info("通过网络监听获取到评论摘要 (新版API)")
                except (json.JSONDecodeError, ValueError) as e:
                    logger.debug("解析评论 API 响应失败: %s", e)
        except Exception as e:
            logger.debug("未捕获到评论 API 响应: %s", e)
        finally:
            tab.listen.stop()

        # 如果 API 没拿到，从 DOM 解析
        if not summary:
            summary = self._extract_from_dom(tab)

        return summary

    def _extract_from_dom(self, tab) -> Dict[str, object]:
        """从页面可见文本动态提取评论数据。

        核心设计：
        - 不依赖任何 CSS class、HTML 标签或 DOM 结构
        - 用 JavaScript 提取 document.body.innerText（纯可见文本）
        - 在纯文本上做模式匹配，京东无论怎么改版结构，用户看到的文字是相对稳定的
        - 每种指标有多个候选正则，优先级从高到低尝试
        """
        summary = {}

        # 第一步：用 JS 获取页面纯可见文本（不含 HTML 标签）
        visible_text = ""
        try:
            visible_text = tab.run_js("return document.body.innerText") or ""
        except Exception:
            visible_text = ""

        # 兜底：如果 innerText 为空则回退到 html
        if not visible_text.strip():
            visible_text = tab.html or ""
            # 粗略去标签
            visible_text = re.sub(r"<[^>]+>", " ", visible_text)
            visible_text = re.sub(r"\s+", " ", visible_text)

        logger.debug("页面文本长度: %d 字符", len(visible_text))

        # 第二步：在纯文本上匹配各项指标

        # —— 好评率 ——
        # 可能出现的文字格式：
        #   "好评度 98%"  "好评率：98%"  "好评度98%"
        #   "超99%买家赞不绝口"  "98%好评"
        rate_patterns = [
            r"好评[度率]\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?)\s*%",
            r"超\s*([0-9]+(?:\.[0-9]+)?)\s*%\s*(?:买家|好评)",
            r"([0-9]{2,3}(?:\.[0-9]+)?)\s*%\s*好评",
        ]
        for pattern in rate_patterns:
            m = re.search(pattern, visible_text)
            if m:
                summary["goodRateShow"] = float(m.group(1))
                break

        # —— 评论/评价总数 ——
        # 可能出现的文字格式：
        #   "累计评价 2万+"  "累计评价2万+"  "评价 20000+"
        #   "评论(2万+)"  "全部评价(3.5万+)"  "1234条评价"
        #   "2万+条评论"  "共 2000 条评价"
        count_patterns = [
            (r"累计评[价论]\s*[:：]?\s*([\d.]+)\s*万\s*\+?", True),       # 万为单位
            (r"([\d.]+)\s*万\s*\+?\s*条?\s*评[价论]", True),              # "2万+条评价"
            (r"(?:评[价论]|评分)\s*[（(]\s*([\d.]+)\s*万\s*\+?\s*[）)]", True),   # "评价(2万+)"
            (r"(?:评[价论]|评分)\s*[（(]\s*(\d[\d,]*)\s*\+?\s*[）)]", False),     # "评价(2000)"
            (r"共?\s*(\d[\d,]*)\s*条?\s*评[价论]", False),                # "共 2000 条评价"
            (r"(\d[\d,]*)\s*条\s*评", False),                             # "1234条评"
        ]
        for pattern, is_wan in count_patterns:
            m = re.search(pattern, visible_text)
            if m:
                num = float(m.group(1).replace(",", ""))
                if is_wan:
                    num *= 10000
                summary["commentCount"] = int(num)
                break

        # —— 好评/中评/差评数 ——
        for label, key in [("好评", "goodCount"), ("中评", "generalCount"), ("差评", "poorCount")]:
            # "好评(1.9万+)" 或 "好评 1234"
            m = re.search(rf"{label}\s*[（(]\s*([\d.]+)\s*万?\s*\+?\s*[）)]", visible_text)
            if m:
                num = float(m.group(1))
                if "万" in m.group(0):
                    num *= 10000
                summary[key] = int(num)

        # —— 店铺评分 ——
        # "店铺评分 4.9" 或 "评分：4.9" 或 "商品评分 4.85"
        score_patterns = [
            r"(?:店铺|商品|商家)\s*评分\s*[:：]?\s*([0-9]+(?:\.[0-9]+)?)",
            r"评分\s*[:：]?\s*([0-4]\.[0-9]+|5\.0{0,2})",  # 0.0-5.0 范围
        ]
        for pattern in score_patterns:
            m = re.search(pattern, visible_text)
            if m:
                score = float(m.group(1))
                if 0 < score <= 5.0:
                    summary["score"] = score
                    break

        # —— 推导：有好评数和总数但不知道率 ——
        if "goodRateShow" not in summary and "goodCount" in summary and "commentCount" in summary:
            total = summary["commentCount"]
            if total > 0:
                summary["goodRateShow"] = round(summary["goodCount"] / total * 100, 1)

        if summary:
            logger.info("从页面文本动态提取到: %s", {k: v for k, v in summary.items()})
        else:
            logger.warning("页面文本中未匹配到任何评论数据")
            # 保存原始文本用于调试
            self._save_debug_text(tab, visible_text)

        return summary

    def _save_debug_text(self, tab, visible_text: str) -> None:
        """保存页面文本用于调试（当提取失败时）。"""
        try:
            debug_dir = Path(__file__).resolve().parent / "output" / "debug"
            debug_dir.mkdir(parents=True, exist_ok=True)
            url = tab.url or "unknown"
            product_id = re.search(r"/(\d+)\.html", url)
            name = product_id.group(1) if product_id else "unknown"
            # 保存可见文本
            text_path = debug_dir / f"page_text_{name}.txt"
            text_path.write_text(visible_text[:50000], encoding="utf-8")
            # 保存 HTML
            html_path = debug_dir / f"page_html_{name}.html"
            html_path.write_text(tab.html[:200000] if tab.html else "", encoding="utf-8")
            logger.info("已保存调试文件: %s", text_path)
        except Exception as e:
            logger.debug("保存调试文件失败: %s", e)

    # ------------------------------------------------------------------
    # Metrics extraction
    # ------------------------------------------------------------------

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

    def is_transient_blocked_response(self, text: str) -> bool:
        raw = (text or "").strip()
        if not raw:
            return True
        block_markers = [
            "系统繁忙",
            "访问过于频繁",
            "请求过于频繁",
            "请稍后再试",
            "安全验证",
            "验证码",
        ]
        lower_raw = raw.lower()
        if raw.startswith("<!doctype html") or raw.startswith("<html"):
            return True
        return any(marker in raw for marker in block_markers) or "forbidden" in lower_raw

    # ------------------------------------------------------------------
    # Output normalization
    # ------------------------------------------------------------------

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

    # ------------------------------------------------------------------
    # Main crawl loop
    # ------------------------------------------------------------------

    def crawl(self, mapping_path: Path) -> List[Dict[str, object]]:
        self._init_browser()
        try:
            return self._crawl_loop(mapping_path)
        finally:
            self._close_browser()

    def _crawl_loop(self, mapping_path: Path) -> List[Dict[str, object]]:
        results: List[Dict[str, object]] = []
        rows = self.load_mapping_rows(mapping_path)
        total = len(rows)
        for idx, row in enumerate(rows, start=1):
            logger.info("采集进度 %d/%d — item_id=%s", idx, total, row.item_id)
            try:
                summary = self.fetch_comment_summary_via_browser(row)
                metrics = self.extract_metrics(summary, "")
                crawl_status = "success" if any(metrics.values()) else "empty"
                results.append(self.normalize_row(row, metrics, summary, crawl_status))
                self._on_success()
            except TransientCrawlError as exc:
                logger.warning("商品 %s 采集被拦截: %s", row.source_product_id, exc)
                self._on_block_detected()
                results.append({
                    "item_id": row.item_id,
                    "source_platform": row.source_platform,
                    "source_product_id": row.source_product_id,
                    "source_url": row.source_url,
                    "positive_rate": None,
                    "review_count": None,
                    "shop_score": None,
                    "rating_text": None,
                    "crawl_status": "blocked",
                    "raw_payload": json.dumps({"error": str(exc)}, ensure_ascii=False),
                    "crawled_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
                })
            except Exception as exc:
                logger.error("商品 %s 采集失败: %s", row.source_product_id, exc)
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
            self._random_sleep()
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
    parser = argparse.ArgumentParser(description="京东商品公网满意度指标采集脚本（真实浏览器模式）")
    parser.add_argument("--mapping", default="mappings/product_public_mapping.jd.sample.csv", help="映射 CSV 路径")
    parser.add_argument("--output-dir", default="output", help="输出目录")
    parser.add_argument("--fixture-dir", default="", help="本地夹具目录，用于离线验证解析逻辑")
    parser.add_argument("--timeout", default=DEFAULT_TIMEOUT, type=int, help="页面加载超时时间(秒)")
    parser.add_argument("--sleep-seconds", default=DEFAULT_SLEEP_SECONDS, type=float, help="每次请求之间的基础等待时间")
    parser.add_argument("--max-retries", default=DEFAULT_MAX_RETRIES, type=int, help="临时拦截时的最大重试次数")
    parser.add_argument("--retry-backoff-seconds", default=DEFAULT_RETRY_BACKOFF_SECONDS, type=float, help="每次重试前的退避基础秒数")
    parser.add_argument("--headless", action="store_true", help="无头模式运行（不显示浏览器窗口）")
    parser.add_argument("--browser-path", default="", help="浏览器可执行文件路径")
    parser.add_argument("--browser-user-data-dir", default="", help="Browser user data dir")
    return parser


def resolve_path(base_dir: Path, raw_path: str) -> Path:
    path = Path(raw_path)
    if path.is_absolute():
        return path
    if path.exists():
        return path.resolve()
    return (base_dir / path).resolve()


def first_non_empty(*values: Optional[str]) -> str:
    for value in values:
        if value and str(value).strip():
            return str(value).strip()
    return ""


def resolve_browser_path(cli_value: str) -> str:
    return first_non_empty(
        cli_value,
        os.getenv("PUBLIC_TASK_BROWSER_PATH"),
        os.getenv("CHROME_PATH"),
        os.getenv("CHROMIUM_PATH"),
    )


def resolve_browser_user_data_dir(base_dir: Path, cli_value: str) -> Path:
    configured = first_non_empty(
        cli_value,
        os.getenv("PUBLIC_TASK_BROWSER_PROFILE_DIR"),
    )
    if configured:
        configured_path = Path(configured)
        if configured_path.is_absolute():
            return configured_path
        return (base_dir / configured_path).resolve()
    return (base_dir / "output" / "browser-profile").resolve()


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    args = build_parser().parse_args()
    base_dir = Path(__file__).resolve().parent
    mapping_path = resolve_path(base_dir, args.mapping)
    output_dir = resolve_path(base_dir, args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    fixture_dir = resolve_path(base_dir, args.fixture_dir) if args.fixture_dir else None
    browser_path = resolve_browser_path(args.browser_path)
    browser_user_data_dir = resolve_browser_user_data_dir(base_dir, args.browser_user_data_dir)

    crawler = JDPublicSatisfactionCrawler(
        timeout=args.timeout,
        sleep_seconds=args.sleep_seconds,
        max_retries=args.max_retries,
        retry_backoff_seconds=args.retry_backoff_seconds,
        fixture_dir=fixture_dir,
        headless=args.headless,
        browser_path=browser_path,
        browser_user_data_dir=browser_user_data_dir,
    )
    rows = crawler.crawl(mapping_path)

    csv_path = output_dir / "jd_product_public_metrics.csv"
    json_path = output_dir / "jd_product_public_metrics.json"
    write_csv(csv_path, rows)
    write_json(json_path, rows)

    success = sum(1 for r in rows if r["crawl_status"] == "success")
    blocked = sum(1 for r in rows if r["crawl_status"] == "blocked")
    failed = sum(1 for r in rows if r["crawl_status"] in ("failed", "empty"))
    print(f"共采集 {len(rows)} 条商品公网满意度指标记录。")
    print(f"  成功: {success}  被拦截: {blocked}  失败/空: {failed}")
    print(f"CSV 输出: {csv_path}")
    print(f"JSON 输出: {json_path}")
    print("说明：该脚本只采集京东商品公开评价摘要，不生成用户级 buy/fav/cart/pv 行为。")


if __name__ == "__main__":
    main()
