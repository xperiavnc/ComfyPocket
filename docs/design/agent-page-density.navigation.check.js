async (page) => {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const base = "http://127.0.0.1:8772";
  const inspect = async (phone) => phone.evaluate((element) => {
    const nav = element.querySelector(".ad-bottom-nav");
    const phoneRect = element.getBoundingClientRect();
    const rect = nav.getBoundingClientRect();
    const style = getComputedStyle(nav);
    const active = nav.querySelector('[aria-current="page"]');
    const activeRect = active.getBoundingClientRect();
    const selection = getComputedStyle(active, "::before");
    const buttons = Array.from(nav.querySelectorAll("button"));
    const icon = active.querySelector("[data-lucide]");
    const iconStyle = getComputedStyle(icon);
    return {
      phoneWidth:phoneRect.width, phoneHeight:phoneRect.height,
      navWidth:rect.width, navHeight:rect.height,
      sideMargin:rect.left - phoneRect.left - 1,
      radius:style.borderRadius, borderTop:style.borderTopWidth,
      background:style.backgroundColor,
      text:getComputedStyle(buttons[0]).color,
      accent:getComputedStyle(active).color,
      labels:buttons.map((button) => button.textContent.trim()),
      activeLabel:active.dataset.label,
      selectionHeight:activeRect.height - parseFloat(selection.top) - parseFloat(selection.bottom),
      selectionRadius:selection.borderRadius,
      selectionBackground:selection.backgroundColor,
      iconShadow:iconStyle.boxShadow, iconBackground:iconStyle.backgroundColor,
      minimumButtonWidth:Math.min(...buttons.map((button) => button.getBoundingClientRect().width)),
      minimumButtonHeight:Math.min(...buttons.map((button) => button.getBoundingClientRect().height)),
      composerClear:element.querySelector(".ad-composer").getBoundingClientRect().bottom <= rect.top,
    };
  });
  await page.setViewportSize({ width:768, height:1100 });
  await page.emulateMedia({ colorScheme:"dark" });
  await page.goto(base + "/baseline");
  let frame = page.frames().find((item) => item.parentFrame());
  await frame.waitForFunction(() => document.readyState === "complete");
  const baseline = await inspect(frame.locator('[data-design="generation"]'));
  assert(baseline.sideMargin === 0 && baseline.borderTop === "1px", "Expected the superseded full-width bar");
  await frame.locator('[data-design="generation"]').screenshot({ path:"agent-nav-baseline-dark.png" });
  const layouts = [];
  const helpers = [];
  for (const route of ["candidate", "offline"]) {
    await page.goto(base + "/" + route);
    frame = page.frames().find((item) => item.parentFrame());
    await frame.waitForFunction(() => document.readyState === "complete");
    const root = frame.locator("#agent-density-preview");
    const health = await frame.evaluate(() => ({
      ready:document.getElementById("agent-density-preview").dataset.previewReady,
      fallback:document.getElementById("agent-density-preview").dataset.optionalHelperFallback || null,
      reports:window.__agentLoadingProbe.reports,
      resources:window.__agentLoadingProbe.resources,
      svgIcons:document.querySelectorAll("#agent-density-preview svg[data-lucide]").length,
      emptyIcons:Array.from(document.querySelectorAll("#agent-density-preview i[data-lucide]")).filter((icon) => !icon.textContent.trim()).length,
    }));
    assert(health.ready === "true" && !health.reports.length && !health.emptyIcons, JSON.stringify(health));
    if (route === "offline") {
      assert(health.resources.length === 3 && health.fallback === "floating-core,floating-dom,lucide", "Outage coverage incomplete");
      assert(health.svgIcons === 0, "Outage unexpectedly loaded remote icons");
    } else {
      assert(health.svgIcons > 0, "Normal helper rendering was not exercised");
    }
    helpers.push({ route, ...health });
    for (const variant of ["generation", "conversation"]) {
      if (variant === "conversation") await root.getByRole("button", { name:"下一个方案", exact:true }).click();
      const phone = frame.locator(`[data-design="${variant}"]`);
      for (const theme of ["dark", "light"]) {
        await page.emulateMedia({ colorScheme:theme });
        for (const [viewportWidth, width] of [[352,320],[392,360],[768,400]]) {
          await page.setViewportSize({ width:viewportWidth, height:1100 });
          await frame.waitForFunction((size) => window.innerWidth === size, viewportWidth - 32);
          const actual = await inspect(phone);
          const item = { route, variant, theme, width, ...actual };
          layouts.push(item);
          const expectedSide = Math.min(44, Math.max(8, (width - 2 - 248) / 2));
          assert(Math.abs(actual.sideMargin - expectedSide) < 1, `Shared side margin differs: ${JSON.stringify(item)}`);
          assert(actual.navHeight === 52 && actual.selectionHeight === 44, `Capsule geometry differs: ${JSON.stringify(item)}`);
          assert(actual.radius === "999px" && actual.selectionRadius === "999px" && actual.borderTop === "0px", `Capsule style differs: ${JSON.stringify(item)}`);
          assert(actual.minimumButtonWidth >= 48 && actual.minimumButtonHeight >= 48 && actual.composerClear, `Navigation targets/clearance differ: ${JSON.stringify(item)}`);
          assert(actual.activeLabel === "Agent", "Selected destination changed");
          // Icon text is only a fallback; destination labels remain separate.
          const labels = await phone.locator(".ad-bottom-nav button > span").allTextContents();
          assert(labels.join("|") === "主页|控制|Agent|应用|画廊", `Destinations changed: ${labels}`);
          const expectedColors = theme === "dark"
            ? ["rgb(23, 29, 40)", "rgb(232, 238, 247)", "rgb(107, 158, 255)"]
            : ["rgb(255, 255, 255)", "rgb(26, 29, 36)", "rgb(59, 111, 217)"];
          assert([actual.background, actual.text, actual.accent].join("|") === expectedColors.join("|"), `Shared palette differs: ${JSON.stringify(item)}`);
          assert(actual.iconShadow === "none" && actual.iconBackground === "rgba(0, 0, 0, 0)", "Selection is still attached only to the icon");
          assert(actual.phoneHeight === baseline.phoneHeight, "Changed the canvas height");
          if (width === 400) await phone.screenshot({ path:`agent-nav-${route}-${variant}-${theme}.png` });
          if (width === 320 && theme === "dark") await phone.screenshot({ path:`agent-nav-${route}-${variant}-narrow.png` });
        }
      }
      const fixedBefore = await inspect(phone);
      await phone.evaluate((element) => element.style.setProperty("--ad-radius", "12px"));
      const fixedAfter = await inspect(phone);
      assert(JSON.stringify(fixedBefore) === JSON.stringify(fixedAfter), "Content radius control changed shared navigation");
      await phone.evaluate((element) => element.style.setProperty("--ad-radius", "22px"));
      for (const label of ["主页", "控制", "Agent", "应用", "画廊"]) {
        await phone.locator(`.ad-bottom-nav [data-label="${label}"]`).click();
        assert((await phone.locator(".ad-local-message").textContent()).includes(label), "Navigation demo feedback missing");
        assert(await phone.locator('.ad-bottom-nav [aria-current="page"]').getAttribute("data-label") === "Agent", "Demo changed the current Agent page");
      }
    }
  }
  await page.emulateMedia({ colorScheme:"dark" });
  await page.setViewportSize({ width:768, height:1100 });
  await page.goto(base + "/candidate");
  return { status:"PASS", baseline, layouts, helpers, navigationClicks:20, fixedChromeRadiusChecks:4 };
}
