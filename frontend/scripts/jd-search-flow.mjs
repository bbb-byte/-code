const SEARCH_INPUT_SELECTORS = [
  ".jd_pc_search_bar_react_search_input",
  "#key",
  "input[name='keyword']",
  "input[clstag*='search']",
  ".search-m input[type='text']",
  ".search-form input[type='text']",
];

const SEARCH_RESULT_SELECTORS = [
  ".plugin_goodsCardWrapper[data-sku]",
  "li.gl-item[data-sku]",
  "[data-sku]",
];

const START_PAGE_URL = "https://www.jd.com/";

export async function searchKeywordLikeHuman(page, keyword, logger = console) {
  await ensureSearchEntry(page, logger);
  await pause(page, 700, 1400);
  await moveMouseNaturally(page);

  const input = await waitForVisibleLocator(page, SEARCH_INPUT_SELECTORS, 15000);
  await focusInputSafely(input);
  await pause(page, 200, 500);
  await page.keyboard.press("ControlOrMeta+A");
  await pause(page, 120, 260);
  await page.keyboard.press("Backspace");
  await pause(page, 180, 360);
  await typeLikeHuman(page, keyword);
  await pause(page, 300, 800);
  await input.press("Enter", { delay: randomBetween(80, 160) });

  await waitForSearchResults(page, logger);
  await pause(page, 1200, 2200);
}

export async function humanizeSearchResultsPage(page) {
  await moveMouseNaturally(page);
  await pause(page, 800, 1500);
  await page.mouse.wheel(0, randomBetween(320, 700));
  await pause(page, 600, 1200);
  await page.mouse.wheel(0, randomBetween(180, 420));
  await pause(page, 500, 1100);
}

async function ensureSearchEntry(page, logger) {
  const url = page.url() || "";
  if (url.includes("jd.com")) {
    const input = await firstVisibleLocator(page, SEARCH_INPUT_SELECTORS);
    if (input) {
      return;
    }
  }

  logger.log(`Opening JD home page before search: ${START_PAGE_URL}`);
  await page.goto(START_PAGE_URL, { waitUntil: "domcontentloaded", timeout: 60000 });
  await waitForVisibleLocator(page, SEARCH_INPUT_SELECTORS, 20000);
}

async function waitForSearchResults(page, logger) {
  await page.waitForLoadState("domcontentloaded", { timeout: 60000 }).catch(() => {});
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const currentUrl = page.url() || "";
    if (currentUrl.includes("search.jd.com/Search")) {
      const resultNode = await firstVisibleLocator(page, SEARCH_RESULT_SELECTORS);
      if (resultNode) {
        return;
      }
    }

    const maybeRiskOrLogin = await page.evaluate(() => {
      const html = document.documentElement?.outerHTML || "";
      return {
        isLogin: location.href.includes("passport.jd.com"),
        hasRisk: html.includes("JDR_shields") || html.includes("risk_handler"),
      };
    }).catch(() => ({ isLogin: false, hasRisk: false }));

    if (maybeRiskOrLogin.isLogin || maybeRiskOrLogin.hasRisk) {
      return;
    }

    await pause(page, 600, 1200);
  }

  logger.log("JD search results did not fully stabilize before timeout.");
}

async function typeLikeHuman(page, text) {
  for (const char of String(text || "")) {
    await page.keyboard.type(char, { delay: randomBetween(90, 220) });
    if (Math.random() < 0.18) {
      await pause(page, 120, 320);
    }
  }
}

async function moveMouseNaturally(page) {
  const points = [
    [randomBetween(180, 420), randomBetween(120, 240)],
    [randomBetween(420, 760), randomBetween(140, 320)],
    [randomBetween(260, 560), randomBetween(180, 360)],
  ];
  for (const [x, y] of points) {
    await page.mouse.move(x, y, { steps: randomBetween(8, 20) });
    await pause(page, 40, 140);
  }
}

async function firstVisibleLocator(page, selectors) {
  for (const selector of selectors) {
    const locator = page.locator(selector).first();
    if (await locator.isVisible().catch(() => false)) {
      return locator;
    }
  }
  return null;
}

async function waitForVisibleLocator(page, selectors, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const locator = await firstVisibleLocator(page, selectors);
    if (locator) {
      return locator;
    }
    await pause(page, 150, 250);
  }
  throw new Error(`No visible locator found for selectors: ${selectors.join(", ")}`);
}

async function focusInputSafely(locator) {
  await locator.evaluate((element) => {
    element.focus();
    if (typeof element.select === "function") {
      element.select();
    }
  });
}

async function pause(page, minMs, maxMs) {
  await page.waitForTimeout(randomBetween(minMs, maxMs));
}

function randomBetween(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}
