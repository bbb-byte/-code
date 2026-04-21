import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright-core";
import { buildPersistentContextOptions, resolveBrowserConfig } from "./browser-config.mjs";
import { humanizeSearchResultsPage, searchKeywordLikeHuman } from "./jd-search-flow.mjs";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const frontendDir = path.resolve(__dirname, "..");
const projectRoot = path.resolve(frontendDir, "..");
const browserConfig = resolveBrowserConfig(frontendDir);
const userDataDir = browserConfig.profileDir;
const debugDir = path.join(projectRoot, "crawler", "output", "debug");

const keyword = process.argv.slice(2).join(" ").trim() || "apple smartphone";
const debugHtmlPath = path.join(debugDir, "jd_browser_search_after_login.html");
const storageStatePath = path.join(debugDir, "jd_browser_storage_state.json");

fs.mkdirSync(userDataDir, { recursive: true });
fs.mkdirSync(debugDir, { recursive: true });

console.log(`Opening Chrome for JD search check: ${keyword}`);
console.log(`Profile dir: ${userDataDir}`);
if (browserConfig.browserPath) {
  console.log(`Browser executable: ${browserConfig.browserPath}`);
} else if (browserConfig.browserChannel) {
  console.log(`Browser channel: ${browserConfig.browserChannel}`);
} else {
  console.log("Browser executable: auto-detect");
}
console.log("If JD asks you to log in, please complete login in the opened browser window.");

const context = await chromium.launchPersistentContext(userDataDir, {
  ...buildPersistentContextOptions({
    browserPath: browserConfig.browserPath,
    browserChannel: browserConfig.browserChannel,
    headless: false,
  }),
});

try {
  const page = context.pages()[0] ?? await context.newPage();
  await searchKeywordLikeHuman(page, keyword, console);

  const deadline = Date.now() + 8 * 60 * 1000;
  let result = null;

  while (Date.now() < deadline) {
    await humanizeSearchResultsPage(page);
    await page.waitForTimeout(1800);
    let snapshot;
    try {
      snapshot = await page.evaluate(() => {
        const html = document.documentElement.outerHTML;
        const itemCount = document.querySelectorAll("li.gl-item").length;
        const skuCount = document.querySelectorAll("[data-sku]").length;
        const isLogin = location.href.includes("passport.jd.com") || document.title.includes("登录");
        const hasRisk = html.includes("JDR_shields") || html.includes("risk_handler");
        return {
          href: location.href,
          title: document.title,
          itemCount,
          skuCount,
          isLogin,
          hasRisk,
          html,
          text: document.body ? document.body.innerText.slice(0, 600) : "",
        };
      });
    } catch (error) {
      console.log(`Page is navigating, waiting for it to settle: ${error.message}`);
      continue;
    }

    console.log(
      JSON.stringify(
        {
          href: snapshot.href,
          title: snapshot.title,
          itemCount: snapshot.itemCount,
          skuCount: snapshot.skuCount,
          isLogin: snapshot.isLogin,
          hasRisk: snapshot.hasRisk,
        },
        null,
        2,
      ),
    );

    result = snapshot;
    if (snapshot.itemCount > 0 || snapshot.skuCount > 0) {
      break;
    }
  }

  if (!result) {
    throw new Error("No page snapshot captured.");
  }

  fs.writeFileSync(debugHtmlPath, result.html, "utf8");
  await context.storageState({ path: storageStatePath });

  console.log(`Saved HTML to: ${debugHtmlPath}`);
  console.log(`Saved storage state to: ${storageStatePath}`);

  if (result.itemCount > 0 || result.skuCount > 0) {
    console.log("SUCCESS: JD search results are visible in browser automation.");
  } else if (result.isLogin) {
    console.log("RESULT: still on JD login page; search results not reached yet.");
  } else if (result.hasRisk) {
    console.log("RESULT: risk-control page detected in browser automation.");
  } else {
    console.log("RESULT: page opened, but no searchable JD result list was detected.");
  }
} finally {
  await context.close();
}
