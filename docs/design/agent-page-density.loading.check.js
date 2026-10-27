async (page) => {
  const assert = (condition, message) => { if (!condition) throw new Error(message); };
  // The fixture places a reporter before the fragment, matching the installed
  // host's event-handler order. It records locally instead of calling host APIs.
  // Baseline/candidate fixture CSP excludes the helpers' CDN, so each request
  // fails deterministically with its original URL and the normal error event.
  const readProbe = async () => {
    const frame = page.frames().find((item) => item.parentFrame());
    await frame.waitForFunction(() => document.readyState === "complete");
    return { frame, probe:await frame.evaluate(() => window.__agentLoadingProbe) };
  };
  await page.goto("http://127.0.0.1:8771/baseline");
  const baseline = await readProbe();
  assert(baseline.probe.resources.length === 3, `Baseline did not exercise all helper loads: ${JSON.stringify(baseline.probe)}`);
  assert(baseline.probe.reports.filter((item) => item.type === "resource").length === 3, `Expected original load failure: ${JSON.stringify(baseline.probe)}`);
  await page.goto("http://127.0.0.1:8771/candidate");
  const candidate = await readProbe();
  const fallback = await candidate.frame.evaluate(() => {
    const root = document.getElementById("agent-density-preview");
    return {
      ready:root.dataset.previewReady,
      helpers:root.dataset.optionalHelperFallback,
      emptyIconCount:Array.from(root.querySelectorAll("i[data-lucide]")).filter((icon) => !icon.textContent.trim()).length,
      hasSvgIcons:root.querySelectorAll("svg[data-lucide]").length > 0
    };
  });
  assert(candidate.probe.resources.length === 3, `Candidate did not exercise all helper loads: ${JSON.stringify(candidate.probe)}`);
  assert(candidate.probe.resources.every((item) => item.hasOwnErrorHandler), "Optional helper lacks explicit rendering fallback");
  assert(!candidate.probe.reports.length, `Unexpected candidate report: ${JSON.stringify(candidate.probe)}`);
  assert(fallback.ready === "true", "Candidate did not finish initialization");
  assert(fallback.helpers === "floating-core,floating-dom,lucide", `Missing helper fallback state: ${JSON.stringify(fallback)}`);
  assert(fallback.emptyIconCount === 0, `Empty controls remain: ${JSON.stringify(fallback)}`);
  assert(!fallback.hasSvgIcons, "Outage test unexpectedly loaded the icon library");
  await candidate.frame.locator('[data-design="generation"]').screenshot({ path:"agent-loading-offline.png" });
  // Negative control: an unknown required script must still reach the reporter.
  await candidate.frame.evaluate(() => {
    const script = document.createElement("script");
    script.src = "https://unpkg.com/agent-required-fixture.js";
    document.body.appendChild(script);
  });
  await candidate.frame.waitForFunction(() => window.__agentLoadingProbe.reports.some((item) => item.source?.endsWith("/agent-required-fixture.js")));
  // A thrown application exception and an unhandled rejection must also remain
  // visible; the repair never installs a blanket suppressor.
  await candidate.frame.evaluate(() => {
    const script = document.createElement("script");
    script.textContent = "throw new Error('agent-required-exception-fixture')";
    document.body.appendChild(script);
    Promise.reject(new Error("agent-required-rejection-fixture"));
  });
  await candidate.frame.waitForFunction(() => window.__agentLoadingProbe.reports.some((item) => item.message === "agent-required-rejection-fixture"));
  const critical = await candidate.frame.evaluate(() => window.__agentLoadingProbe.reports);
  assert(critical.some((item) => item.type === "resource" && item.source.endsWith("/agent-required-fixture.js")), "Required script failure was hidden");
  assert(critical.some((item) => item.type === "exception" && item.message === "agent-required-exception-fixture"), "Application exception was hidden");
  assert(critical.some((item) => item.type === "rejection" && item.message === "agent-required-rejection-fixture"), "Unhandled rejection was hidden");
  await page.reload();
  const fresh = await readProbe();
  assert(!fresh.probe.reports.length, "Fresh repaired preview produced a host error");
  return {
    status:"PASS",
    scenario:"Fixture CSP blocks all three host-injected optional helper requests",
    baseline:baseline.probe,
    modified:candidate.probe,
    fallback,
    negativeControls:critical,
    scope:"Local harness reproduces the installed host error-reporting branch; final in-conversation rendering is emitted separately."
  };
}
