async (page) => {
  const errors = [];
  const onError = (error) => errors.push(error.message);
  page.on("pageerror", onError);
  const frame = page.frames().find((item) => item.parentFrame());
  const root = frame.locator("#agent-density-preview");
  const results = { minimumTargetCssPx:48, layouts:[], interactions:[], runtimeErrors:errors };
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  const inspect = async (phone, variant, theme, requestedWidth) => {
    const result = await phone.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      const buttons = Array.from(element.querySelectorAll("button")).filter((button) => button.getClientRects().length);
      const smallTargets = buttons.map((button) => {
        const bounds = button.getBoundingClientRect();
        return { label:button.getAttribute("aria-label") || button.textContent.trim(), width:bounds.width, height:bounds.height };
      }).filter((button) => button.width < 48 || button.height < 48);
      const structuralSelectors = [".ad-page",".ad-header",".ad-workspace-bar",".ad-main",".ad-settings-row",".ad-composer",".ad-bottom-nav",".ad-compose-tools",".ad-task-card"];
      const overflow = structuralSelectors.flatMap((selector) => Array.from(element.querySelectorAll(selector)).filter((node) => node.getClientRects().length && node.scrollWidth > node.clientWidth + 2).map(() => selector));
      const outside = buttons.map((button) => {
        const bounds = button.getBoundingClientRect();
        return { label:button.getAttribute("aria-label") || button.textContent.trim(), left:bounds.left, right:bounds.right, top:bounds.top, bottom:bounds.bottom };
      }).filter((button) => button.left < rect.left - 1 || button.right > rect.right + 1 || button.top < rect.top - 1 || button.bottom > rect.bottom + 1);
      const composer = element.querySelector(".ad-composer").getBoundingClientRect();
      const nav = element.querySelector(".ad-bottom-nav").getBoundingClientRect();
      return { width:rect.width, height:rect.height, smallTargets, overflow, outside, composerAboveNavigation:composer.bottom <= nav.top, visibleButtonCount:buttons.length };
    });
    const item = { variant, theme, requestedWidth, ...result };
    results.layouts.push(item);
    assert(Math.abs(result.width-requestedWidth)<1, `Viewport had not settled: ${JSON.stringify(item)}`);
    assert(!result.smallTargets.length, `Small touch targets: ${JSON.stringify(item)}`);
    assert(!result.overflow.length, `Horizontal overflow: ${JSON.stringify(item)}`);
    assert(!result.outside.length, `Controls outside phone: ${JSON.stringify(item)}`);
    assert(result.composerAboveNavigation, `Composer/nav overlap: ${JSON.stringify(item)}`);
  };
  for (const variant of ["generation","conversation"]) {
    if (variant === "conversation") await root.getByRole("button", { name:"下一个方案", exact:true }).click();
    const phone = frame.locator(`[data-design="${variant}"]`);
    await phone.waitFor({ state:"visible" });
    for (const theme of ["dark","light"]) {
      await page.emulateMedia({ colorScheme:theme });
      for (const [viewportWidth, requestedWidth] of [[352,320],[392,360],[768,400]]) {
        await page.setViewportSize({ width:viewportWidth, height:1100 });
        await frame.waitForFunction((width) => window.innerWidth === width, viewportWidth - 32);
        await inspect(phone, variant, theme, requestedWidth);
        if (viewportWidth === 768) await phone.screenshot({ path:`agent-${variant}-${theme}.png` });
      }
    }
    await page.emulateMedia({ colorScheme:"dark" });
    await page.setViewportSize({ width:768, height:1100 });
    const negativeTrigger = phone.locator('[data-action="negative"]').filter({ visible:true }).first();
    await negativeTrigger.click();
    const dialog = phone.locator('[role="dialog"]');
    assert(await dialog.isVisible(), `${variant}: missing negative detail`);
    assert(await dialog.locator("dt").count() === 6, `${variant}: translations lost`);
    const modalState = await phone.evaluate((element) => {
      const modal = element.querySelector('[role="dialog"]');
      const phoneRect = element.getBoundingClientRect(), modalRect = modal.getBoundingClientRect();
      return {
        inert:element.querySelector(".ad-page").inert,
        named:!!document.getElementById(modal.getAttribute("aria-labelledby")),
        fits:modalRect.top >= phoneRect.top && modalRect.bottom <= phoneRect.bottom + 1
      };
    });
    assert(modalState.inert && modalState.named && modalState.fits, `${variant}: modal invalid`);
    await phone.screenshot({ path:`agent-${variant}-details-dark.png` });
    await page.keyboard.press("Escape");
    assert(!await dialog.count(), `${variant}: Escape failed to close details`);
    assert(await negativeTrigger.evaluate((button) => document.activeElement === button), `${variant}: focus not restored`);
    results.interactions.push({ variant, action:"negative details + 6 translations + inert + label + Escape + focus return", status:"PASS" });
    await phone.locator('[data-action="settings"]').filter({ visible:true }).first().click();
    await phone.locator('textarea[name="prompt"]').fill("手指自然，保持原有构图。");
    await phone.getByRole("button", { name:"保存设置", exact:true }).click();
    assert((await phone.locator(".ad-local-message").textContent()).includes("已保存"), `${variant}: settings not saved`);
    results.interactions.push({ variant, action:"open/edit/save drawing settings", status:"PASS" });
    if (variant === "generation") {
      await phone.getByRole("button", { name:"Chat", exact:true }).click();
      assert(await phone.locator(".ad-chat-view").isVisible(), "Chat workspace not visible");
      await phone.getByRole("button", { name:"绘图", exact:true }).click();
      assert(await phone.locator(".ad-draw-view").isVisible(), "Drawing workspace not restored");
      await phone.getByRole("button", { name:"放大查看预览示意图", exact:true }).click();
    } else {
      await phone.getByRole("button", { name:"Chat", exact:true }).click();
      assert(await phone.getByRole("button", { name:"Chat", exact:true }).getAttribute("aria-pressed") === "true", "Input mode did not change");
      await phone.getByRole("button", { name:"绘图", exact:true }).click();
      await phone.getByRole("button", { name:"查看预览", exact:true }).click();
    }
    assert(await phone.locator('[data-panel="preview"]').isVisible(), `${variant}: preview did not open`);
    await phone.getByRole("button", { name:"关闭面板", exact:true }).click();
    results.interactions.push({ variant, action:"mode switch + image preview", status:"PASS" });
    await phone.getByRole("textbox", { name:"消息", exact:true }).fill("保留构图，调整手部细节。");
    await phone.getByRole("button", { name:"发送消息", exact:true }).click();
    assert((await phone.locator(".ad-local-message").textContent()).includes("保留构图"), `${variant}: send did not update visible state`);
    assert(await phone.getByRole("textbox", { name:"消息", exact:true }).inputValue() === "", `${variant}: message input not cleared`);
    await phone.getByRole("textbox", { name:"消息", exact:true }).fill("回车发送验证");
    await phone.getByRole("textbox", { name:"消息", exact:true }).press("Enter");
    assert((await phone.locator(".ad-local-message").textContent()).includes("回车发送验证"), `${variant}: Enter did not send`);
    results.interactions.push({ variant, action:"send button + Enter + visible local status update", status:"PASS" });
    await phone.getByRole("button", { name:"更多会话操作", exact:true }).click();
    await phone.getByRole("button", { name:"清空当前对话", exact:true }).click();
    assert(await phone.getByRole("button", { name:"确认清空", exact:true }).isVisible(), `${variant}: clear lacks confirmation`);
    await phone.getByRole("button", { name:"保留对话", exact:true }).click();
    assert(await phone.locator(".ad-user-message").first().evaluate((node) => !node.hidden), `${variant}: cancel clear removed messages`);
    await phone.getByRole("button", { name:"更多会话操作", exact:true }).click();
    await phone.getByRole("button", { name:"清空当前对话", exact:true }).click();
    await phone.getByRole("button", { name:"确认清空", exact:true }).click();
    assert(await phone.locator(".ad-user-message").first().evaluate((node) => node.hidden), `${variant}: confirm clear did not clear messages`);
    results.interactions.push({ variant, action:"clear confirmation + cancel preserves + confirm clears local sample", status:"PASS" });
  }
  const heights = results.layouts.filter((item) => item.width === 400).map((item) => item.height);
  assert(Math.max(...heights)-Math.min(...heights) < 1, `Variants use different canvas heights: ${JSON.stringify(heights)}`);
  assert(!errors.length, `Runtime errors: ${JSON.stringify(errors)}`);
  results.sameCanvas = { width:400, heights, status:"PASS" };
  results.status = "PASS";
  await page.reload();
  await page.emulateMedia({ colorScheme:"dark" });
  page.off("pageerror", onError);
  return results;
}
