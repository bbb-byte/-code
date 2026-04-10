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
    mapping: "",
    outputDir: path.join(projectRoot, "crawler", "output", "browser_metrics"),
    userDataDir: path.join(frontendDir, ".jd-chrome-profile"),
    headless: true,
    maxProducts: 3,
    sleepSeconds: 5,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--mapping") result.mapping = argv[++i] || "";
    else if (arg === "--output-dir") result.outputDir = argv[++i] || result.outputDir;
    else if (arg === "--user-data-dir") result.userDataDir = argv[++i] || result.userDataDir;
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

function normalizePercent(value) {
  if (value == null || value === "") return "";
  const numeric = Number(String(value).replace("%", ""));
  if (!Number.isFinite(numeric)) return "";
  return numeric > 1 ? (numeric / 100).toString() : numeric.toString();
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

function extractFromHtml(html) {
  const raw = html || "";
  const text = raw.replace(/\s+/g, " ");
  const matchFirst = (patterns) => {
    for (const pattern of patterns) {
      const match = text.match(pattern);
      if (match && match[1]) return match[1];
    }
    return "";
  };

  const positiveRate = matchFirst([
    /evaluationRate(?:=|%3D)([0-9]+(?:\.[0-9]+)?)/i,
    /好评率[：:\s]*([0-9]+(?:\.[0-9]+)?)%/i,
  ]);
  const reviewCount = matchFirst([
    /commentNum(?:=|%3D)([^&"'<>\\s]+)/i,
    /评论[^0-9]{0,8}([0-9]+(?:\.[0-9]+)?万?\+?)/i,
  ]);
  const shopScore = matchFirst([
    /(?:\?|&|amp;)score(?:=|%3D)([0-9]+(?:\.[0-9]+)?)/i,
    /店铺评分[：:\s]*([0-9]+(?:\.[0-9]+)?)/i,
  ]);
  const ratingText = matchFirst([
    /(好评率[：:\s]*[0-9]+(?:\.[0-9]+)?%)/i,
  ]);

  return {
    positive_rate: normalizePercent(positiveRate),
    review_count: normalizeCount(reviewCount),
    shop_score: shopScore || "",
    rating_text: ratingText || "",
  };
}

async function collectOne(page, row, sleepSeconds) {
  await page.goto(row.source_url, { waitUntil: "domcontentloaded", timeout: 60000 });
  await page.waitForTimeout(Math.max(1000, sleepSeconds * 1000));
  const html = await page.content();
  const metrics = extractFromHtml(html);
  const hasAnyMetric = metrics.positive_rate || metrics.review_count || metrics.shop_score || metrics.rating_text;
  return {
    item_id: row.item_id,
    source_platform: row.source_platform || "jd",
    source_product_id: row.source_product_id,
    source_url: row.source_url,
    positive_rate: metrics.positive_rate,
    review_count: metrics.review_count,
    shop_score: metrics.shop_score,
    rating_text: metrics.rating_text,
    crawl_status: hasAnyMetric ? "success" : "empty",
    raw_payload: JSON.stringify({
      extracted_from: "browser_product_page",
      html_length: html.length,
      title: await page.title(),
    }, null, 0),
    crawled_at: new Date().toISOString().replace(/\.\d{3}Z$/, "Z"),
  };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const mappingPath = resolvePath(args.mapping);
  const outputDir = resolvePath(args.outputDir);
  const userDataDir = resolvePath(args.userDataDir);
  if (!mappingPath) {
    throw new Error("--mapping is required.");
  }

  const rows = parseCsv(fs.readFileSync(mappingPath, "utf8")).slice(0, args.maxProducts > 0 ? args.maxProducts : undefined);
  fs.mkdirSync(outputDir, { recursive: true });
  fs.mkdirSync(userDataDir, { recursive: true });

  const context = await chromium.launchPersistentContext(userDataDir, {
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

  const page = context.pages()[0] ?? await context.newPage();
  const resultRows = [];
  try {
    for (const row of rows) {
      const result = await collectOne(page, row, args.sleepSeconds);
      resultRows.push(result);
      console.log(JSON.stringify({
        item_id: result.item_id,
        source_product_id: result.source_product_id,
        crawl_status: result.crawl_status,
        positive_rate: result.positive_rate,
        review_count: result.review_count,
        shop_score: result.shop_score,
      }));
    }
  } finally {
    await context.close();
  }

  const csvPath = path.join(outputDir, "jd_product_public_metrics.csv");
  const jsonPath = path.join(outputDir, "jd_product_public_metrics.json");
  writeCsv(csvPath, resultRows);
  fs.writeFileSync(jsonPath, JSON.stringify(resultRows, null, 2), "utf8");
  console.log(`CSV output: ${csvPath}`);
  console.log(`JSON output: ${jsonPath}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
