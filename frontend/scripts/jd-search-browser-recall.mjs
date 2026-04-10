import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const frontendDir = path.resolve(__dirname, "..");
const projectRoot = path.resolve(frontendDir, "..");
const edgePath = "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe";

function parseArgs(argv) {
  const result = {
    products: "",
    output: "",
    topK: 5,
    maxProducts: 50,
    storageState: path.join(projectRoot, "crawler", "output", "debug", "jd_browser_storage_state.json"),
    debugDir: path.join(projectRoot, "crawler", "output", "debug"),
    userDataDir: path.join(frontendDir, ".jd-edge-profile"),
    headless: true,
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--products") result.products = argv[++i] || "";
    else if (arg === "--output") result.output = argv[++i] || "";
    else if (arg === "--top-k") result.topK = Number(argv[++i] || 5);
    else if (arg === "--max-products") result.maxProducts = Number(argv[++i] || 50);
    else if (arg === "--storage-state") result.storageState = argv[++i] || result.storageState;
    else if (arg === "--debug-dir") result.debugDir = argv[++i] || result.debugDir;
    else if (arg === "--user-data-dir") result.userDataDir = argv[++i] || result.userDataDir;
    else if (arg === "--headless") result.headless = (argv[++i] || "true") !== "false";
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
    "public_title",
    "public_brand",
    "public_category",
    "public_price",
    "search_keyword",
    "rank",
    "candidate_source",
  ];
  const content = [
    headers.join(","),
    ...rows.map((row) => headers.map((header) => csvEscape(row[header])).join(",")),
  ].join("\n");
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, "utf8");
}

function extractCategoryKeyword(categoryName) {
  if (!categoryName) return "";
  const parts = categoryName.split(/[>/|.]/).map((part) => part.trim()).filter(Boolean);
  return parts.length ? parts[parts.length - 1] : "";
}

function buildKeyword(product) {
  const brandPart = (product.brand || "").trim();
  const categoryPart = extractCategoryKeyword(product.category_name || "");
  return [brandPart, categoryPart].filter(Boolean).join(" ") || categoryPart || brandPart || String(product.item_id);
}

async function recallOne(page, product, topK) {
  const keyword = buildKeyword(product);
  const url = `https://search.jd.com/Search?keyword=${encodeURIComponent(keyword)}`;
  await page.goto(url, { waitUntil: "domcontentloaded", timeout: 60000 });

  let snapshot = null;
  const deadline = Date.now() + 5 * 60 * 1000;
  while (Date.now() < deadline) {
    await page.waitForTimeout(4000);
    try {
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight / 2));
    } catch (error) {
      console.log(`Page is navigating for keyword "${keyword}", waiting to settle: ${error.message}`);
      continue;
    }
    await page.waitForTimeout(1500);

    try {
      snapshot = await page.evaluate((limit) => {
        const nodes = Array.from(document.querySelectorAll(".plugin_goodsCardWrapper[data-sku], li.gl-item[data-sku]")).slice(0, limit);
        return {
          href: location.href,
          title: document.title,
          isLogin: location.href.includes("passport.jd.com") || document.title.includes("登录"),
          hasRisk: document.documentElement.outerHTML.includes("JDR_shields") || document.documentElement.outerHTML.includes("risk_handler"),
          html: document.documentElement.outerHTML,
          candidates: nodes.map((node, index) => {
            const sku = node.getAttribute("data-sku") || "";
            const link = node.querySelector("a[href*='item.jd.com'], ._wrapper_1hy8u_24, ._imagew_ncezi_40");
            const href = link?.href || link?.getAttribute?.("href") || "";
            const fallbackUrl = sku ? `https://item.jd.com/${sku}.html` : "";
            const titleNode = node.querySelector(".p-name em, ._text_jedor_40, ._title_ncezi_65, h5[title]");
            const shopNode = node.querySelector(".p-shop a, ._shopFloor_cynmp_29 ._limit_cynmp_17, ._shopFloor_cynmp_29 ._name_cynmp_8");
            const priceNode = node.querySelector(".p-price i, ._container_1agky_2 ._price_1agky_18, ._price_t0dwj_14");
            const titleText = titleNode
              ? (titleNode.getAttribute("title") || titleNode.textContent || "").replace(/\s+/g, " ").trim()
              : "";
            const priceText = priceNode ? priceNode.textContent.replace(/\s+/g, "").trim().replace(/^¥/, "") : "";
            return {
              source_product_id: sku,
              source_url: href.startsWith("//") ? `https:${href}` : (href || fallbackUrl),
              public_title: titleText,
              public_brand: shopNode ? shopNode.textContent.replace(/\s+/g, " ").trim() : "",
              public_category: "",
              public_price: priceText,
              rank: index + 1,
            };
          }).filter((item) => item.public_title),
        };
      }, topK);
    } catch (error) {
      console.log(`Page is navigating for keyword "${keyword}", waiting to settle: ${error.message}`);
      continue;
    }

    if (!snapshot.isLogin || snapshot.candidates.length > 0 || snapshot.hasRisk) {
      break;
    }
    console.log(`Waiting for JD login to complete for keyword "${keyword}"...`);
  }

  if (!snapshot) {
    throw new Error(`No page snapshot captured for keyword: ${keyword}`);
  }

  return { keyword, ...snapshot };
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const productsPath = resolvePath(args.products);
  const outputPath = resolvePath(args.output);
  const storageStatePath = resolvePath(args.storageState);
  const debugDir = resolvePath(args.debugDir);
  const userDataDir = resolvePath(args.userDataDir);

  if (!productsPath || !outputPath) {
    throw new Error("Both --products and --output are required.");
  }
  fs.mkdirSync(debugDir, { recursive: true });
  fs.mkdirSync(userDataDir, { recursive: true });

  const productRows = parseCsv(fs.readFileSync(productsPath, "utf8"));
  const products = productRows.slice(0, args.maxProducts > 0 ? args.maxProducts : productRows.length);

  const context = await chromium.launchPersistentContext(userDataDir, {
    headless: args.headless,
    executablePath: edgePath,
    viewport: { width: 1440, height: 960 },
    locale: "zh-CN",
  });

  const page = await context.newPage();
  const allRows = [];
  let loginHits = 0;
  let riskHits = 0;

  try {
    for (const product of products) {
      const result = await recallOne(page, product, args.topK);
      if (result.isLogin) loginHits += 1;
      if (result.hasRisk) riskHits += 1;
      if (!result.candidates.length) {
        const debugPath = path.join(debugDir, `jd_browser_recall_${product.item_id}.html`);
        fs.writeFileSync(debugPath, result.html, "utf8");
      }
      for (const candidate of result.candidates) {
        allRows.push({
          item_id: product.item_id,
          source_platform: "jd",
          source_product_id: candidate.source_product_id,
          source_url: candidate.source_url,
          public_title: candidate.public_title,
          public_brand: candidate.public_brand,
          public_category: candidate.public_category,
          public_price: candidate.public_price,
          search_keyword: result.keyword,
          rank: candidate.rank,
          candidate_source: "jd_browser_search",
        });
      }
      console.log(
        JSON.stringify({
          itemId: product.item_id,
          keyword: result.keyword,
          title: result.title,
          href: result.href,
          candidateCount: result.candidates.length,
          login: result.isLogin,
          risk: result.hasRisk,
        }),
      );
    }
  } finally {
    await context.close();
  }

  writeCsv(outputPath, allRows);
  console.log(`Loaded ${productRows.length} products.`);
  console.log(`Processed ${products.length} products.`);
  console.log(`Wrote ${allRows.length} candidate rows.`);
  if (loginHits) console.log(`Login page encountered for ${loginHits} products.`);
  if (riskHits) console.log(`Risk-control page encountered for ${riskHits} products.`);
  console.log(`Candidate file: ${outputPath}`);

  if (!allRows.length) {
    process.exit(2);
  }
}

main().catch((error) => {
  console.error(error.stack || String(error));
  process.exit(1);
});
