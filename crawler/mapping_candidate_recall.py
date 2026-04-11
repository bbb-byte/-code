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
DEFAULT_SLEEP_SECONDS = 15.0
DEFAULT_SLEEP_JITTER = (5.0, 15.0)

# 遇到频繁限制时的退避策略
BACKOFF_BASE_SECONDS = 120       # 首次退避 2 分钟
BACKOFF_MAX_SECONDS = 600        # 最长退避 10 分钟
MAX_CONSECUTIVE_RISK = 5         # 连续被拦截超过此次数，终止当前批次


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
        use_existing_browser: bool = True,
        cdp_port: int = 9222,
    ):
        self.timeout = timeout
        self.fixture_dir = fixture_dir
        self.allow_sample_fallback = allow_sample_fallback
        self.request_delay = max(0.0, request_delay)
        self.shared_sample_hits = 0
        self.risk_page_hits = 0
        self.consecutive_risk_hits = 0
        self.debug_dir: Optional[Path] = None
        self.browser: Optional[Chromium] = None
        self.browser_path = browser_path
        self.headless = headless
        self.use_existing_browser = use_existing_browser
        self.cdp_port = cdp_port
        self._owns_browser = False  # 是否由脚本自己启动的浏览器（决定退出时是否关闭）
        self._search_tab = None     # 用于搜索的专用标签页
        self._cancel_signal_path: Optional[Path] = None  # 取消信号文件路径

    def _init_browser(self) -> None:
        """初始化浏览器连接。
        
        自动处理浏览器启动：
        1. 优先尝试连接已运行的 Chrome（CDP 调试端口）
        2. 如果连接失败，自动启动一个带调试端口的 Chrome
           使用专用 Debug Profile 目录，登录态会持久保存
        """
        if self.fixture_dir:
            return

        # 专用的 Debug Profile 目录（登录态在这里持久保存，下次不用重新登录）
        debug_profile_dir = str(Path.home() / "AppData" / "Local" / "Google" / "Chrome" / "Debug Profile")

        if self.use_existing_browser:
            # 第一步：尝试连接已经在运行的 Chrome
            try:
                co = ChromiumOptions()
                co.set_local_port(self.cdp_port)
                self.browser = Chromium(co)
                self._owns_browser = False
                logger.info("✓ 已成功接管现有 Chrome 浏览器（端口 %d）", self.cdp_port)
                return
            except Exception:
                logger.info("未检测到已运行的调试 Chrome，正在自动启动...")

            # 第二步：自动启动带调试端口的 Chrome
            try:
                co = ChromiumOptions()
                browser_path = self.browser_path or r"C:\Program Files\Google\Chrome\Application\chrome.exe"
                co.set_browser_path(browser_path)
                co.set_local_port(self.cdp_port)
                co.set_user_data_path(debug_profile_dir)
                if self.headless:
                    co.headless()
                self.browser = Chromium(co)
                self._owns_browser = True
                logger.info("✓ 已自动启动带调试端口的 Chrome（端口 %d）", self.cdp_port)
                logger.info("  登录态保存在: %s", debug_profile_dir)
                logger.info("  首次使用请在弹出的 Chrome 中登录京东")
                return
            except Exception as e:
                logger.warning("自动启动 Chrome 失败: %s", e)
                logger.info("正在回退到备用端口的独立浏览器模式...")

        # 回退：启动独立的 Chrome 实例（备用端口）
        co = ChromiumOptions()
        browser_path = self.browser_path or r"C:\Program Files\Google\Chrome\Application\chrome.exe"
        co.set_browser_path(browser_path)
        co.set_local_port(19222)
        co.set_user_data_path(debug_profile_dir)
        if self.headless:
            co.headless()
        self.browser = Chromium(co)
        self._owns_browser = True
        logger.info("Chrome 浏览器已独立启动（备用端口 19222）")

    def _close_browser(self) -> None:
        if self.browser:
            try:
                if self._owns_browser:
                    # 只关闭由脚本自己启动的浏览器
                    self.browser.quit()
                else:
                    # 接管的浏览器不要退出，关闭我们新开的搜索标签页
                    if self._search_tab:
                        try:
                            self._search_tab.close()
                        except Exception:
                            pass
                    logger.info("搜索标签页已关闭，用户的 Chrome 浏览器保持不变")
            except Exception:
                pass

    def _random_sleep(self) -> None:
        """随机等待，模拟人类搜索间隔（15~30 秒）。"""
        jitter = random.uniform(*DEFAULT_SLEEP_JITTER)
        duration = self.request_delay + jitter
        logger.info("等待 %.0f 秒后继续下一次搜索...", duration)
        time.sleep(duration)

    def _warmup(self) -> None:
        """首次搜索前先访问京东首页，像正常用户一样'热身'。"""
        if self.fixture_dir:
            return
        # 如果是接管的浏览器，新开一个标签页专门给爬虫用
        if not self._owns_browser:
            self._search_tab = self.browser.new_tab()
            logger.info("已在你的 Chrome 中新开了一个标签页用于搜索")
        else:
            self._search_tab = self.browser.latest_tab

        tab = self._search_tab
        logger.info("正在访问京东首页热身...")
        tab.get("https://www.jd.com")
        tab.wait(random.uniform(3, 5))
        # 模拟随便浏览一下
        try:
            tab.scroll.down(random.randint(200, 600))
            tab.wait(random.uniform(1, 3))
            tab.scroll.down(random.randint(100, 400))
            tab.wait(random.uniform(1, 2))
        except Exception:
            pass
        logger.info("首页热身完成，即将开始搜索")

    def _backoff_wait(self, attempt: int) -> None:
        """指数退避等待：第 1 次 2 分钟，第 2 次 4 分钟，第 3 次 8 分钟… 最长 10 分钟。"""
        wait_seconds = min(BACKOFF_BASE_SECONDS * (2 ** attempt), BACKOFF_MAX_SECONDS)
        # 加一点随机抖动
        wait_seconds += random.uniform(10, 30)
        logger.warning("=== 触发访问频繁限制，开始退避等待 %.0f 秒（约 %.1f 分钟）===", wait_seconds, wait_seconds / 60)
        if self._interruptible_sleep(wait_seconds):
            return  # 被取消
        logger.info("退避等待结束，尝试继续搜索...")

    def _random_sleep(self) -> None:
        """每次搜索之间的随机等待，模拟人类浏览节奏。"""
        delay = random.uniform(15, 30)
        logger.debug("等待 %.1f 秒后继续下一次搜索...", delay)
        self._interruptible_sleep(delay)

    def _interruptible_sleep(self, total_seconds: float) -> bool:
        """可被取消信号中断的 sleep。每 3 秒检查一次取消文件。
        返回 True 表示被取消。"""
        elapsed = 0.0
        interval = 3.0
        while elapsed < total_seconds:
            chunk = min(interval, total_seconds - elapsed)
            time.sleep(chunk)
            elapsed += chunk
            if self._cancel_signal_path and self._cancel_signal_path.exists():
                logger.info("在等待期间检测到取消信号，立即中止")
                return True
        return False

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

        # 使用真实浏览器访问搜索页（含自动重试+退避）
        max_retries = 3
        for attempt in range(max_retries):
            html = self._do_search(keyword)

            if not self.is_risk_page(html):
                # 搜索成功，重置连续风控计数
                self.consecutive_risk_hits = 0
                return html

            # 遇到风控
            self.risk_page_hits += 1
            self.consecutive_risk_hits += 1

            tab = self._search_tab or self.browser.latest_tab
            current_url = tab.url or ""

            # 判断是否是需要人工验证的页面（验证码/滑块）
            if "risk_handler" in current_url or "验证" in (html or ""):
                logger.warning("=" * 60)
                logger.warning("⚠️  检测到京东人机验证页面！")
                logger.warning("⚠️  请在 Chrome 浏览器中手动点击「快速验证」按钮")
                logger.warning("⚠️  完成验证后脚本会自动继续搜索")
                logger.warning("=" * 60)

                # 等待用户手动完成验证（最多等 5 分钟）
                max_wait = 300  # 5 分钟
                waited = 0
                poll_interval = 5
                while waited < max_wait:
                    time.sleep(poll_interval)
                    waited += poll_interval
                    # 检查取消信号
                    if self._cancel_signal_path and self._cancel_signal_path.exists():
                        logger.info("在等待验证期间检测到取消信号，中止任务")
                        return html
                    # 检查页面是否已经离开验证页
                    try:
                        current_url = tab.url or ""
                        if "risk_handler" not in current_url and "验证" not in (tab.title or ""):
                            logger.info("✓ 验证已通过！继续搜索...")
                            # 验证通过后，回首页冷却一下再继续
                            tab.get("https://www.jd.com")
                            tab.wait(random.uniform(3, 5))
                            self.consecutive_risk_hits = 0
                            break
                    except Exception:
                        pass
                    if waited % 30 == 0:
                        logger.info("仍在等待验证完成... (已等待 %d 秒)", waited)
                else:
                    logger.error("等待验证超时（5 分钟），跳过此次搜索")

                # 验证通过后重新搜索当前关键词
                html = self._do_search(keyword)
                if not self.is_risk_page(html):
                    self.consecutive_risk_hits = 0
                    return html
            else:
                # 普通频繁限制，使用退避策略
                logger.warning("搜索 '%s' 被风控拦截（第 %d/%d 次重试）", keyword, attempt + 1, max_retries)

                if self.consecutive_risk_hits >= MAX_CONSECUTIVE_RISK:
                    logger.error("连续 %d 次被风控拦截，停止继续搜索以免进一步封禁。", self.consecutive_risk_hits)
                    return html

                # 指数退避
                self._backoff_wait(attempt)

                # 退避结束后，回首页"恢复"一下再重试
                try:
                    tab.get("https://www.jd.com")
                    tab.wait(random.uniform(3, 6))
                    tab.scroll.down(random.randint(200, 500))
                    tab.wait(random.uniform(2, 4))
                except Exception:
                    pass

        return html

    def _find_search_box(self, tab):
        """在当前页面查找搜索输入框，兼容新旧版京东。"""
        # 新版首页: class="jd_pc_search_bar_react_search_input"
        box = tab.ele('css:.jd_pc_search_bar_react_search_input', timeout=3)
        if box:
            logger.debug("找到搜索框 (新版首页 class)")
            return box
        # 旧版/搜索结果页: id="key"
        box = tab.ele('css:#key', timeout=3)
        if box:
            logger.debug("找到搜索框 (旧版 #key)")
            return box
        # 通用: aria-label="搜索" 的 input
        box = tab.ele('css:input[aria-label="搜索"]', timeout=3)
        if box:
            logger.debug("找到搜索框 (aria-label)")
            return box
        return None

    def _find_search_button(self, tab):
        """在当前页面查找搜索按钮，兼容新旧版京东。"""
        # 新版首页: class="jd_pc_search_bar_react_search_btn"
        btn = tab.ele('css:.jd_pc_search_bar_react_search_btn', timeout=3)
        if btn:
            return btn
        # 旧版搜索页: class="button" 
        btn = tab.ele('css:button.button', timeout=2)
        if btn:
            return btn
        # aria-label="搜索"的 button
        btn = tab.ele('css:button[aria-label="搜索"]', timeout=2)
        if btn:
            return btn
        # 文本包含"搜索"的 button
        btn = tab.ele('tag:button@text():搜索', timeout=2)
        if btn:
            return btn
        return None

    def _do_search(self, keyword: str) -> str:
        """执行一次搜索：模拟人类在搜索框输入关键词并点击搜索按钮。
        
        绝不直接跳转 search.jd.com URL，因为京东能区分"用户点击搜索"
        和"直接 URL 导航"，后者会被判定为机器行为并触发频繁限制。
        """
        tab = self._search_tab or self.browser.latest_tab

        # 确保当前页面是京东域名（首页或搜索页都行），这样才有搜索框
        current_url = tab.url or ""
        if "jd.com" not in current_url:
            tab.get("https://www.jd.com")
            tab.wait(random.uniform(2, 4))

        # 找搜索框 —— 兼容新版首页和旧版搜索结果页
        # 新版首页: <input class="jd_pc_search_bar_react_search_input">
        # 旧版/搜索页: <input id="key">
        search_box = self._find_search_box(tab)
        if not search_box:
            # 兜底：回首页再试一次
            logger.warning("未找到搜索框，正在重新访问京东首页...")
            tab.get("https://www.jd.com")
            tab.wait(random.uniform(3, 5))
            search_box = self._find_search_box(tab)

        if search_box:
            # 清空并逐字输入关键词，模拟真人打字
            search_box.clear()
            tab.wait(random.uniform(0.3, 0.8))
            for char in keyword:
                search_box.input(char)
                time.sleep(random.uniform(0.05, 0.2))
            tab.wait(random.uniform(0.5, 1.5))

            # 点击搜索按钮 —— 兼容新旧版
            search_btn = self._find_search_button(tab)
            if search_btn:
                search_btn.click()
                logger.debug("已点击搜索按钮")
            else:
                # 如果实在找不到按钮，按回车键提交
                search_box.input('\n')
                logger.debug("未找到搜索按钮，使用回车键提交搜索")

            # 等待页面加载
            tab.wait.doc_loaded(timeout=self.timeout)
        else:
            # 极端兜底：search_box 完全找不到
            logger.error("搜索框完全不可用，回退到 URL 导航（可能触发频繁限制）")
            tab.get(f"{JD_SEARCH_URL}?keyword={keyword}")

        tab.wait(random.uniform(2, 4))

        # 模拟人类滚动浏览
        try:
            tab.scroll.down(random.randint(300, 800))
            tab.wait(random.uniform(0.5, 1.5))
            tab.scroll.down(random.randint(100, 400))
        except Exception:
            pass
        tab.wait(random.uniform(1, 2))

        # 等待搜索结果加载（兼容新旧京东结构）
        try:
            tab.wait.eles_loaded('[data-sku]', timeout=self.timeout)
        except Exception:
            logger.warning("搜索结果未完全加载: %s", keyword)

        return tab.html

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

        # ========== 新版京东（2025+）结构 ==========
        # 商品卡片格式: <div ... data-sku="xxx"> ... </div>
        # 标题在: <span class="_text_jedor_..." title="...">标题</span>
        #    或: title="标题" 在父级 wrapper div 上
        # 价格在: <span class="_price_...">¥价格</span>
        # 店铺在: <span class="_name_cynmp_...">店铺名</span>

        # 策略1: 先用新版结构 (data-sku 在 div 上)
        new_pattern = re.compile(
            r'<div[^>]*\bdata-sku="(?P<sku>\d+)"[^>]*>(?P<body>.*?)(?=<div[^>]*\bdata-sku="\d+"[^>]*>|$)',
            re.S,
        )
        matches = list(new_pattern.finditer(html))

        # 策略2: 如果新版没匹配到，用旧版结构 (gl-item)
        if not matches:
            old_pattern = re.compile(
                r'<li[^>]*class="[^"]*gl-item[^"]*"[^>]*data-sku="(?P<sku>\d+)"[^>]*>(?P<body>.*?)</li>',
                re.S,
            )
            matches = list(old_pattern.finditer(html))

        for rank, match in enumerate(matches, start=1):
            if rank > top_k:
                break
            body = match.group("body")
            sku = match.group("sku")

            # 提取商品 URL
            source_url = self.extract_first(
                body,
                r'<a[^>]+href="(?P<url>//item\.jd\.com/\d+\.html|https?://item\.jd\.com/\d+\.html)"',
            )
            if not source_url:
                source_url = f"https://item.jd.com/{sku}.html"
            elif source_url.startswith("//"):
                source_url = urljoin("https:", source_url)

            # 提取标题 —— 多种策略
            title = ""
            # 新版: <span class="_text_jedor_..." title="xxx">
            title_match = re.search(r'class="[^"]*_text_jedor_[^"]*"[^>]*>(?P<value>.*?)</span>', body, re.S)
            if title_match:
                title = self.clean_html(title_match.group("value"))
            if not title:
                # 新版备选: title 属性
                title_attr = re.search(r'title="(?P<value>[^"]{10,})"', body)
                if title_attr:
                    title = self.clean_html(title_attr.group("value"))
            if not title:
                # 旧版: p-name > em
                title = self.clean_html(
                    self.extract_first(
                        body,
                        r'<div[^>]*class="[^"]*p-name[^"]*"[^>]*>.*?<em[^>]*>(?P<value>.*?)</em>',
                    )
                )

            # 提取价格 —— 多种策略
            public_price = None
            # 新版: <span class="_price_..."><i>¥</i>6068<span>.</span><span>32</span></span>
            price_span = re.search(r'class="[^"]*_price_[^"]*"[^>]*>(?P<inner>.*?)</span>', body, re.S)
            if price_span:
                # 去掉所有标签，只留数字和小数点
                price_text = re.sub(r'<[^>]+>', '', price_span.group("inner"))
                price_text = re.sub(r'[^\d.]', '', price_text)
                public_price = self.parse_price(price_text)
            if public_price is None:
                # 旧版: p-price > i
                public_price = self.parse_price(
                    self.extract_first(
                        body,
                        r'<div[^>]*class="[^"]*p-price[^"]*"[^>]*>.*?<i[^>]*>(?P<value>.*?)</i>',
                    )
                )

            # 提取店铺名 —— 多种策略
            shop_text = ""
            # 新版: <span class="_name_cynmp_...">
            shop_match = re.search(r'class="[^"]*_name_cynmp_[^"]*"[^>]*>(?P<value>.*?)</span>', body, re.S)
            if shop_match:
                shop_text = self.clean_html(shop_match.group("value"))
            if not shop_text:
                # 新版备选: _shopFloor 区域内的链接
                shop_match2 = re.search(r'class="[^"]*_shopFloor[^"]*"[^>]*>.*?<a[^>]*>(?P<value>.*?)</a>', body, re.S)
                if shop_match2:
                    shop_text = self.clean_html(shop_match2.group("value"))
            if not shop_text:
                # 旧版: p-shop > a
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
    parser.add_argument("--cdp-port", default=9222, type=int, help="Chrome 调试端口（默认 9222），用于接管已打开的浏览器")
    parser.add_argument("--standalone", action="store_true", help="不接管现有浏览器，启动独立 Chrome 实例")
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
        use_existing_browser=not args.standalone,
        cdp_port=args.cdp_port,
    )
    recall.set_debug_dir(debug_dir)
    recall._cancel_signal_path = output_path.parent / ".cancel_signal"
    recall._init_browser()

    # 首次搜索前先热身
    recall._warmup()

    try:
        rows: List[Dict[str, object]] = []
        debug_files: List[Path] = []
        total = len(product_list)
        keyword_cache: Dict[str, List] = {}
        aborted = False

        for idx, product in enumerate(product_list, start=1):
            # 检查取消信号
            cancel_signal = output_path.parent / ".cancel_signal"
            if cancel_signal.exists():
                logger.info("检测到取消信号，正在保存已有数据并退出...")
                try:
                    cancel_signal.unlink()
                except Exception:
                    pass
                write_rows(output_path, rows)
                aborted = True
                print(f"任务已被用户取消，已保存 {len(rows)} 条候选数据。")
                break

            keyword = recall.build_keyword(product.brand, product.category_name)
            
            if keyword in keyword_cache:
                logger.info("搜索进度 %d/%d — keyword='%s' (使用缓存，跳过抓取)", idx, total, keyword)
                candidates = keyword_cache[keyword]
            else:
                logger.info("搜索进度 %d/%d — keyword='%s'", idx, total, keyword)
                html = recall.fetch_search_page(product.item_id, keyword)

                # 检查是否连续被拦截，fetch_search_page 内部已做重试
                if recall.consecutive_risk_hits >= MAX_CONSECUTIVE_RISK:
                    logger.error("连续风控拦截次数过多，中止本批次。已完成的数据将保存。")
                    aborted = True
                    # 保存当前已有数据
                    write_rows(output_path, rows)
                    break

                candidates = recall.parse_search_results(
                    html=html,
                    brand_hint=product.brand,
                    category_hint=product.category_name,
                    top_k=args.top_k,
                )
                keyword_cache[keyword] = candidates

                if not candidates:
                    debug_path = recall.save_debug_html(product.item_id, keyword, html)
                    if debug_path:
                        debug_files.append(debug_path)
                recall._random_sleep()

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

            # 每处理完一个商品就增量保存，防止数据丢失
            if idx % 3 == 0 or idx == total:
                write_rows(output_path, rows)
                logger.info("已增量保存 %d 条候选记录到 %s", len(rows), output_path)

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
        if aborted:
            print(f"WARNING: 本批次因连续风控拦截而提前中止，仅保存了 {len(rows)} 条已成功召回的数据。")
            print("建议等待 30 分钟后重新运行，或减小 --max-products 的值分批处理。")
    finally:
        recall._close_browser()


if __name__ == "__main__":
    main()
