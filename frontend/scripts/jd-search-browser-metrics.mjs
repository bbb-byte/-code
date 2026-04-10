import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const frontendDir = path.resolve(__dirname, "..");
const projectRoot = path.resolve(frontendDir, "..");
const chromePath = "C:/Program Files/Google/Chrome/Application/chrome.exe";

function parseArgs(argv) {
  const result = {
    candidates: "",
    output: path.join(projectRoot, "crawler", "output", "jd_search_browser_metrics.csv"),
    userDataDir: path.join(frontendDir, ".jd-chrome-profile"),
    debugDir: path.join(projectRoot, "crawler", "output", "debug"),
    cdpUrl: "",
    useCurrentPage: false,
    headless: true,
    maxProducts: 3,
    sleepSeconds: 5,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--candidates") result.candidates = argv[++i] || "";
    else if (arg === "--output") result.output = argv[++i] || result.output;
    else if (arg === "--user-data-dir") result.userDataDir = argv[++i] || result.userDataDir;
    else if (arg === "--debug-dir") result.debugDir = argv[++i] || result.debugDir;
    else if (arg === "--cdp-url") result.cdpUrl = argv[++i] || result.cdpUrl;
    else if (arg === "--use-current-page") result.useCurrentPage = (argv[++i] || "true") !== "false";
    else if (arg === "--headless") result.headless = (argv[++i] || "true") !== "false";
    else if (arg === "--max-products") result.maxProducts = Number(argv[++i] || result.maxProducts);
    else if (arg === "--sleep-seconds") result.sleepSeconds = Number(argv[++i] || result.sleepSeconds);
  }
  return result;
}

function resolvePath(rawPath) {
  if (!rawPath) return rawPath;
  if (path.isAbsolute(rawPath)) return rawPath;
  return path.resolve(projectRoot, rawPath);
}

function parseCsvLine(line) {
  const values = [];
  let current = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (ch === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
    } else if (ch === "," && !inQuotes) {
      values.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  values.push(current);
  return values;
}

function parseCsv(text) {
  const lines = text.split(/\r?\n/).filter((line) => line.trim() !== "");
  if (!lines.length) return [];
  const headers = parseCsvLine(lines[0]).map((item) => item.trim());
  return lines.slice(1).map((line) => {
    const values = parseCsvLine(line);
    const row = {};
    headers.forEach((header, index) => {
      row[header] = values[index] ?? "";
    });
    return row;
  });
}

function csvEscape(value) {
  const text = value == null ? "" : String(value);
  if (/[",\r\n]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

function normalizePercent(value) {
  if (value == null || value === "") return "";
  const numeric = Number(String(value).replace("%", ""));
  if (!Number.isFinite(numeric)) return "";
  return numeric > 1 ? String(numeric / 100) : String(numeric);
}

function normalizeCount(text) {
  if (!text) return "";
  const value = String(text).trim().replace(/,/g, "").replace(/\+/g, "");
  if (!value) return "";
  if (value.includes("万")) {
    const numeric = Number(value.replace("万", ""));
    return Number.isFinite(numeric) ? String(Math.round(numeric * 10000)) : "";
  }
  const numeric = Number(value);
  return Number.isFinite(numeric) ? String(Math.round(numeric)) : "";
}

function writeCsv(filePath, rows) {
  const headers = [
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
  ];
  const content = [
    headers.join(","),
    ...rows.map((row) => headers.map((header) => csvEscape(row[header])).join(",")),
  ].join("\n");
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, "utf8");
}

async function collectOne(page, row, sleepSeconds) {
  const keyword = row.search_keyword || row.public_title || row.source_product_id;
  const searchUrl = `https://search.jd.com/Search?keyword=${encodeURIComponent(keyword)}`;
  await page.goto(searchUrl, { waitUntil: "domcontentloaded", timeout: 60000 });
  await page.waitForTimeout(Math.max(1000, sleepSeconds * 1000));
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 2));
  await page.waitForTimeout(1500);
  const pageHtml = await page.content();

  const extracted = await page.evaluate((sourceProductId) => {
    const parseHrefValue = (href, key) => {
      if (!href) return "";
      const patterns = [
        new RegExp(`[?&]${key}=([^&]+)`, "i"),
        new RegExp(`${key}%3D([^&]+)`, "i"),
      ];
      for (const pattern of patterns) {
        const match = href.match(pattern);
        if (match && match[1]) {
          try {
            return decodeURIComponent(match[1]);
          } catch {
            return match[1];
          }
        }
      }
      return "";
    };

    const skuSelector = [
      `.plugin_goodsCardWrapper[data-sku="${sourceProductId}"]`,
      `li.gl-item[data-sku="${sourceProductId}"]`,
      `[data-sku="${sourceProductId}"]`,
    ].join(",");

    const allSkuNodes = Array.from(document.querySelectorAll(".plugin_goodsCardWrapper[data-sku], li.gl-item[data-sku], [data-sku]"));
    const visibleSkus = allSkuNodes
      .map((node) => node.getAttribute("data-sku") || "")
      .filter(Boolean)
      .slice(0, 20);

    const visibleProductLinks = Array.from(document.querySelectorAll('a[href*="item.jd.com/"]'))
      .map((node) => node.getAttribute("href") || node.href || "")
      .filter(Boolean)
      .slice(0, 20);

    const node = document.querySelector(skuSelector);
    if (!node) {
      return {
        found: false,
        title: document.title,
        href: location.href,
        htmlLength: document.documentElement.outerHTML.length,
        visibleSkus,
        visibleProductLinks,
      };
    }

    const cardRoot = node.closest(".plugin_goodsCardWrapper, li.gl-item, [data-sku]") || node;
    const cardHtml = cardRoot.outerHTML || "";
    const chatLink = cardRoot.querySelector('a[href*="chat.jd.com/index.action"]');
    const href = chatLink?.getAttribute("href") || chatLink?.href || "";
    const ratingTextMatch = cardHtml.match(/好评率[：:\s]*[0-9]+(?:\.[0-9]+)?%/i);

    return {
      found: true,
      title: document.title,
      href: location.href,
      htmlLength: cardHtml.length,
      visibleSkus,
      visibleProductLinks,
      metricHref: href,
      positiveRate: parseHrefValue(href, "evaluationRate"),
      reviewCount: parseHrefValue(href, "commentNum"),
      shopScore: parseHrefValue(href, "score"),
      ratingText: ratingTextMatch ? ratingTextMatch[0] : "",
    };
  }, row.source_product_id);

  const result = {
    item_id: row.item_id,
    source_platform: row.source_platform || "jd",
    source_product_id: row.source_product_id,
    source_url: row.source_url,
    positive_rate: extracted.found ? normalizePercent(extracted.positiveRate) : "",
    review_count: extracted.found ? normalizeCount(extracted.reviewCount) : "",
    shop_score: extracted.found ? (extracted.shopScore || "") : "",
    rating_text: extracted.found ? (extracted.ratingText || "") : "",
    crawl_status: extracted.found && (extracted.positiveRate || extracted.reviewCount || extracted.shopScore || extracted.ratingText)
      ? "success"
      : (extracted.found ? "empty" : "not_found"),
    raw_payload: JSON.stringify({
      extracted_from: "browser_search_result",
      keyword,
      found: extracted.found,
      title: extracted.title || "",
      href: extracted.href || "",
      metric_href: extracted.metricHref || "",
      html_length: extracted.htmlLength || 0,
      visible_skus: extracted.visibleSkus || [],
      visible_product_links: extracted.visibleProductLinks || [],
    }),
    crawled_at: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    pageHtml,
  };
  return result;
}

async function collectFromCurrentPage(page, row) {
  const pageHtml = await page.content();
  const extracted = await page.evaluate((sourceProductId) => {
    const parseHrefValue = (href, key) => {
      if (!href) return "";
      const patterns = [
        new RegExp(`[?&]${key}=([^&]+)`, "i"),
        new RegExp(`${key}%3D([^&]+)`, "i"),
      ];
      for (const pattern of patterns) {
        const match = href.match(pattern);
        if (match && match[1]) {
          try {
            return decodeURIComponent(match[1]);
          } catch {
            return match[1];
          }
        }
      }
      return "";
    };

    const allSkuNodes = Array.from(document.querySelectorAll(".plugin_goodsCardWrapper[data-sku], li.gl-item[data-sku], [data-sku]"));
    const visibleSkus = allSkuNodes
      .map((node) => node.getAttribute("data-sku") || "")
      .filter(Boolean)
      .slice(0, 20);

    const node = document.querySelector(
      `.plugin_goodsCardWrapper[data-sku="${sourceProductId}"], li.gl-item[data-sku="${sourceProductId}"], [data-sku="${sourceProductId}"]`,
    );
    if (!node) {
      return {
        found: false,
        title: document.title,
        href: location.href,
        htmlLength: document.documentElement.outerHTML.length,
        visibleSkus,
      };
    }

    const cardRoot = node.closest(".plugin_goodsCardWrapper, li.gl-item, [data-sku]") || node;
    const cardHtml = cardRoot.outerHTML || "";
    const chatLink = cardRoot.querySelector('a[href*="chat.jd.com/index.action"]');
    const href = chatLink?.getAttribute("href") || chatLink?.href || "";
    const ratingTextMatch = cardHtml.match(/好评率[：:\s]*[0-9]+(?:\.[0-9]+)?%/i);

    return {
      found: true,
      title: document.title,
      href: location.href,
      metricHref: href,
      htmlLength: cardHtml.length,
      visibleSkus,
      positiveRate: parseHrefValue(href, "evaluationRate"),
      reviewCount: parseHrefValue(href, "commentNum"),
      shopScore: parseHrefValue(href, "score"),
      ratingText: ratingTextMatch ? ratingTextMatch[0] : "",
    };
  }, row.source_product_id);

  return {
    item_id: row.item_id,
    source_platform: row.source_platform || "jd",
    source_product_id: row.source_product_id,
    source_url: row.source_url,
    positive_rate: extracted.found ? normalizePercent(extracted.positiveRate) : "",
    review_count: extracted.found ? normalizeCount(extracted.reviewCount) : "",
    shop_score: extracted.found ? (extracted.shopScore || "") : "",
    rating_text: extracted.found ? (extracted.ratingText || "") : "",
    crawl_status: extracted.found && (extracted.positiveRate || extracted.reviewCount || extracted.shopScore || extracted.ratingText)
      ? "success"
      : (extracted.found ? "empty" : "not_found"),
    raw_payload: JSON.stringify({
      extracted_from: "browser_current_search_page",
      found: extracted.found,
      title: extracted.title || "",
      href: extracted.href || "",
      metric_href: extracted.metricHref || "",
      html_length: extracted.htmlLength || 0,
      visible_skus: extracted.visibleSkus || [],
    }),
    crawled_at: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
    pageHtml,
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const candidatesPath = resolvePath(args.candidates);
  const outputPath = resolvePath(args.output);
  const userDataDir = resolvePath(args.userDataDir);
  const debugDir = resolvePath(args.debugDir);

  if (!candidatesPath) {
    throw new Error("--candidates is required.");
  }

  const rows = parseCsv(fs.readFileSync(candidatesPath, "utf8")).slice(0, args.maxProducts > 0 ? args.maxProducts : undefined);
  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.mkdirSync(userDataDir, { recursive: true });
  fs.mkdirSync(debugDir, { recursive: true });

  let browser = null;
  let context = null;
  let page = null;
  if (args.cdpUrl) {
    browser = await chromium.connectOverCDP(args.cdpUrl);
    const contexts = browser.contexts();
    const pages = contexts.flatMap((ctx) => ctx.pages());
    page = pages.find((p) => (p.url() || "").includes("search.jd.com/Search")) || pages[0];
    if (!page) {
      throw new Error("No browser page found from CDP connection.");
    }
  } else {
    context = await chromium.launchPersistentContext(userDataDir, {
      headless: args.headless,
      executablePath: chromePath,
      viewport: { width: 1440, height: 960 },
      locale: "zh-CN",
      args: [
        "--disable-blink-features=AutomationControlled",
        "--disable-infobars",
        "--no-first-run",
        "--no-default-browser-check",
      ],
      ignoreDefaultArgs: ["--enable-automation"],
    });
    page = context.pages()[0] ?? await context.newPage();
  }
  const resultRows = [];

  try {
    for (const row of rows) {
      const result = args.useCurrentPage
        ? await collectFromCurrentPage(page, row)
        : await collectOne(page, row, args.sleepSeconds);
      const debugPath = path.join(debugDir, `jd_search_metrics_${row.item_id}_${row.source_product_id}.html`);
      fs.writeFileSync(debugPath, result.pageHtml, "utf8");
      delete result.pageHtml;
      resultRows.push(result);
      console.log(JSON.stringify({
        item_id: result.item_id,
        source_product_id: result.source_product_id,
        crawl_status: result.crawl_status,
        positive_rate: result.positive_rate,
        review_count: result.review_count,
        shop_score: result.shop_score,
        debug_path: debugPath,
      }));
    }
  } finally {
    if (context) {
      await context.close();
    }
    if (browser) {
      await browser.close();
    }
  }

  writeCsv(outputPath, resultRows);
  console.log(`CSV output: ${outputPath}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
