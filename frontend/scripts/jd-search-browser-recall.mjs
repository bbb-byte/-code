import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";
import { buildPersistentContextOptions, connectOverCdpWithFallback, resolveBrowserConfig } from "./browser-config.mjs";
import { humanizeSearchResultsPage, searchKeywordLikeHuman } from "./jd-search-flow.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const frontendDir = path.resolve(__dirname, "..");
const projectRoot = path.resolve(frontendDir, "..");
const browserConfig = resolveBrowserConfig(frontendDir);

function parseArgs(argv) {
  const result = {
    products: "",
    output: "",
    topK: 5,
    maxProducts: 50,
    storageState: path.join(projectRoot, "crawler", "output", "debug", "jd_browser_storage_state.json"),
    debugDir: path.join(projectRoot, "crawler", "output", "debug"),
    userDataDir: browserConfig.profileDir,
    cdpUrl: "",
    headless: true,
    fixtureDir: "",
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
    else if (arg === "--cdp-url") result.cdpUrl = argv[++i] || "";
    else if (arg === "--headless") result.headless = (argv[++i] || "true") !== "false";
    else if (arg === "--fixture-dir") result.fixtureDir = argv[++i] || "";
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

async function withTimeout(promiseFactory, timeoutMs, label) {
  let timer = null;
  try {
    return await Promise.race([
      promiseFactory(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${label} timed out after ${Math.round(timeoutMs / 1000)}s`)), timeoutMs);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
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

async function recallOne(page, keyword, topK) {
  console.log(`Searching JD via search box: "${keyword}"`);
  await searchKeywordLikeHuman(page, keyword, console);

  let snapshot = null;
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    await page.waitForTimeout(2500);
    try {
      await humanizeSearchResultsPage(page);
    } catch (error) {
      console.log(`Page is navigating for keyword "${keyword}", waiting to settle: ${error.message}`);
      continue;
    }
    await page.waitForTimeout(900);

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
            const titleNode = node.querySelector(".p-name em, ._text_jedor_40, ._title_ncezi_65, ._text_1k2fi_48, ._goods_title_container_1k2fi_1 [title], h5[title], span[title]");
            const shopNode = node.querySelector(".p-shop a, ._shopFloor_cynmp_29 ._limit_cynmp_17, ._shopFloor_cynmp_29 ._name_cynmp_8, ._shopFloor_1phiu_30 ._limit_1phiu_18, ._shopFloor_1phiu_30 ._name_1phiu_9");
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

/**
 * 从夹具目录中读取离线搜索页面 HTML，返回候选商品。
 */
function recallFromFixture(fixtureDir, product, topK) {
  const itemFixture = path.join(fixtureDir, `jd_search_${product.item_id}.html`);
  const sampleFixture = path.join(fixtureDir, "jd_search_result.sample.html");
  let html = "";
  if (fs.existsSync(itemFixture)) {
    html = fs.readFileSync(itemFixture, "utf8");
  } else if (fs.existsSync(sampleFixture)) {
    html = fs.readFileSync(sampleFixture, "utf8");
  }
  if (!html) {
    return { keyword: buildKeyword(product), candidates: [], isLogin: false, hasRisk: false, title: "", href: "" };
  }
  // 复用浏览器端相同的选择器逻辑提取候选
  const keyword = buildKeyword(product);
  const candidates = parseHtmlCandidates(html, topK);
  return { keyword, candidates, isLogin: false, hasRisk: false, title: "fixture", href: "fixture" };
}

/**
 * 用正则从 HTML 中提取候选商品（离线/夹具模式）。
 */
function parseHtmlCandidates(html, topK) {
  const candidates = [];
  // 匹配 data-sku 属性
  const skuRegex = /data-sku="(\d+)"/g;
  const skus = [];
  let m;
  while ((m = skuRegex.exec(html)) !== null && skus.length < topK) {
    if (!skus.includes(m[1])) skus.push(m[1]);
  }
  for (let i = 0; i < skus.length; i++) {
    candidates.push({
      source_product_id: skus[i],
      source_url: `https://item.jd.com/${skus[i]}.html`,
      public_title: `Fixture product ${skus[i]}`,
      public_brand: "",
      public_category: "",
      public_price: "",
      rank: i + 1,
    });
  }
  return candidates;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const productsPath = resolvePath(args.products);
  const outputPath = resolvePath(args.output);
  const debugDir = resolvePath(args.debugDir);
  const userDataDir = resolvePath(args.userDataDir);
  const fixtureDir = args.fixtureDir ? resolvePath(args.fixtureDir) : "";

  if (!productsPath || !outputPath) {
    throw new Error("Both --products and --output are required.");
  }
  fs.mkdirSync(debugDir, { recursive: true });

  const productRows = parseCsv(fs.readFileSync(productsPath, "utf8"));
  const products = productRows.slice(0, args.maxProducts > 0 ? args.maxProducts : productRows.length);

  // 夹具模式：直接从离线 HTML 中提取候选，不需要浏览器
  if (fixtureDir && fs.existsSync(fixtureDir)) {
    console.log(`Using fixture directory: ${fixtureDir}`);
    const allRows = [];
    for (const product of products) {
      const result = recallFromFixture(fixtureDir, product, args.topK);
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
      console.log(JSON.stringify({
        itemId: product.item_id,
        keyword: result.keyword,
        candidateCount: result.candidates.length,
        mode: "fixture",
      }));
    }
    writeCsv(outputPath, allRows);
    console.log(`Fixture mode: wrote ${allRows.length} candidate rows.`);
    console.log(`Candidate file: ${outputPath}`);
    if (!allRows.length) process.exit(2);
    return;
  }

  // 浏览器模式：CDP 连接或本地启动
  let browser = null;
  let context = null;
  let page = null;

  if (args.cdpUrl) {
    // CDP 模式：连接宿主机已登录的 Chrome（Docker 容器场景）
    console.log(`Connecting to host browser via CDP: ${args.cdpUrl}`);
    const cdpConnection = await connectOverCdpWithFallback(chromium, args.cdpUrl, console);
    browser = cdpConnection.browser;
    const contexts = browser.contexts();
    const allPages = contexts.flatMap((ctx) => ctx.pages());
    // 优先选择 JD 页面；其次选任意非 chrome:// 内部页；最后新建
    page = allPages.find((p) => (p.url() || "").includes("jd.com"))
      || allPages.find((p) => !(p.url() || "").startsWith("chrome://"))
      || null;
    if (!page) {
      const ctx = contexts.length > 0 ? contexts[0] : await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: "zh-CN" });
      page = await ctx.newPage();
    }
    console.log(`Connected to CDP via ${cdpConnection.connectedUrl}. Current page: ${page.url()}`);
  } else {
    // 本地启动模式
    fs.mkdirSync(userDataDir, { recursive: true });
    context = await chromium.launchPersistentContext(userDataDir, {
      ...buildPersistentContextOptions({
        browserPath: browserConfig.browserPath,
        browserChannel: browserConfig.browserChannel,
        headless: args.headless,
      }),
    });
    page = context.pages()[0] ?? await context.newPage();
  }

  const allRows = [];
  const keywordCache = new Map();
  let loginHits = 0;
  let riskHits = 0;
  let cacheHits = 0;

  try {
    for (let index = 0; index < products.length; index += 1) {
      const product = products[index];
      const keyword = buildKeyword(product);
      let result = keywordCache.get(keyword);
      let cacheHit = true;
      console.log(`Recalling ${index + 1}/${products.length}: item=${product.item_id}, keyword="${keyword}"`);
      if (result) {
        cacheHits += 1;
      } else {
        cacheHit = false;
        try {
          result = await withTimeout(
            () => recallOne(page, keyword, args.topK),
            120000,
            `Recall item ${product.item_id} keyword "${keyword}"`,
          );
        } catch (error) {
          console.log(`Recall failed for item=${product.item_id}, keyword="${keyword}": ${error.message}`);
          result = {
            keyword,
            href: page.url(),
            title: await page.title().catch(() => ""),
            isLogin: false,
            hasRisk: false,
            html: await page.content().catch(() => ""),
            candidates: [],
            error: error.message,
          };
        }
        keywordCache.set(keyword, result);
      }
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
          error: result.error || "",
          cacheHit,
        }),
      );
      writeCsv(outputPath, allRows);
      if (!cacheHit) {
        await page.waitForTimeout(1800 + Math.floor(Math.random() * 2200));
      }
    }
  } finally {
    if (context) {
      await context.close();
    }
    if (browser) {
      await browser.close();
    }
  }

  writeCsv(outputPath, allRows);
  console.log(`Loaded ${productRows.length} products.`);
  console.log(`Processed ${products.length} products.`);
  console.log(`Wrote ${allRows.length} candidate rows.`);
  if (cacheHits) console.log(`Reused cached search results for ${cacheHits} products with duplicate keywords.`);
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
