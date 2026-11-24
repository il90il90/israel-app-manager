// End-to-end test of the admin dashboard in a real browser, with the GitHub API mocked.
//   node tests/dashboard-e2e/dashboard.e2e.mjs
// Covers: sign-in and greeting, listing, adding an app, the draft surviving a reload, a publish
// that hits a conflict and merges, following the publish status, XSS safety, and where the
// token is (and is not) sent.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

// Through require, so a globally installed Playwright (NODE_PATH) works as well as a local one.
const { chromium } = createRequire(import.meta.url)("playwright");

const ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SITE = join(ROOT, "dashboard");
const TOKEN = "github_pat_" + "A1b2C3d4E5".repeat(4);
const REPO = "/repos/il90il90/israel-app-manager";

const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml", ".json": "application/json" };
const server = createServer(async (req, res) => {
  const path = normalize(decodeURIComponent(new URL(req.url, "http://x").pathname)).replace(/^\/+/, "") || "index.html";
  try {
    const body = await readFile(join(SITE, path));
    res.writeHead(200, { "Content-Type": types[extname(path)] ?? "application/octet-stream" });
    res.end(body);
  } catch {
    res.writeHead(404);
    res.end();
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const origin = `http://127.0.0.1:${server.address().port}`;

const shipped = JSON.parse(await readFile(join(ROOT, "catalog/catalog.json"), "utf8"));
shipped.apps[1].name = '<img src=x onerror="window.__xss=1">SeretGram';
let remote = { catalog: structuredClone(shipped), sha: "sha-1" };
const b64 = (obj) => Buffer.from(JSON.stringify(obj, null, 2) + "\n").toString("base64");
const puts = [];
let releaseStatus = null;
const tokenLeaks = [];

const browser = await chromium.launch();
const context = await browser.newContext();
const page = await context.newPage();
const consoleErrors = [];
page.on("console", (m) => { if (m.type() === "error") consoleErrors.push(m.text()); });
page.on("pageerror", (e) => consoleErrors.push(String(e)));

await context.route("**/*", async (route) => {
  const req = route.request();
  const url = new URL(req.url());
  if (url.origin === origin) return route.continue();
  const auth = req.headers()["authorization"];
  if (url.href.includes(TOKEN)) tokenLeaks.push(`token in URL: ${url.href}`);
  const json = (status, body) => route.fulfill({ status, contentType: "application/json", headers: { "access-control-allow-origin": "*" }, body: JSON.stringify(body) });
  if (url.hostname !== "api.github.com") {
    if (auth) tokenLeaks.push(`token sent to ${url.hostname}`);
    return route.fulfill({ status: 404, headers: { "access-control-allow-origin": "*" }, body: "" });
  }
  const p = url.pathname;
  if (p.includes("/actions/")) {
    if (auth) tokenLeaks.push("token sent to the Actions endpoint");
    return json(200, { workflow_runs: puts.length ? [{ status: "completed", conclusion: "success", html_url: "https://github.com/run/1" }] : [] });
  }
  if (auth !== `Bearer ${TOKEN}`) return json(401, { message: "Bad credentials" });
  if (p === "/user") return json(200, { login: "il90il90", name: "Israel Cohen", avatar_url: "https://avatars.example/u.png" });
  if (p === REPO) return json(200, { full_name: "il90il90/israel-app-manager", permissions: { push: true } });
  if (p === `${REPO}/contents/catalog/catalog.json` && req.method() === "GET") {
    return json(200, { sha: remote.sha, encoding: "base64", content: b64(remote.catalog) });
  }
  if (p === `${REPO}/contents/catalog/catalog.json` && req.method() === "PUT") {
    const body = JSON.parse(req.postData());
    puts.push(body);
    if (puts.length === 1) {
      // Someone else published meanwhile: the dashboard must merge, not overwrite.
      remote = { catalog: structuredClone(remote.catalog), sha: "sha-2" };
      remote.catalog.apps[0].tagline.en = "Changed elsewhere";
      return json(409, { message: "catalog/catalog.json does not match sha-1" });
    }
    assert.equal(body.sha, remote.sha, "the second commit must name the newest sha");
    remote = { catalog: JSON.parse(Buffer.from(body.content, "base64").toString("utf8")), sha: "sha-3" };
    releaseStatus = { state: "success", at: new Date().toISOString(), sourceBlob: "sha-3", apps: remote.catalog.apps.length, warnings: [] };
    return json(200, { content: { sha: "sha-3" }, commit: { sha: "c0ffee1234567", html_url: "https://github.com/commit/c0ffee" } });
  }
  if (p === `${REPO}/releases/tags/catalog`) {
    const body = releaseStatus ? `x\n<!-- catalog-status ${JSON.stringify(releaseStatus)} -->` : "x";
    return json(200, { id: 1, body, assets: [] });
  }
  if (p === `${REPO}/branches/main`) return json(200, { commit: { sha: "c0ffee1234567" } });
  if (p === `${REPO}/commits`) return json(200, []);
  return json(404, { message: "Not Found" });
});

const step = (name) => console.log(`- ${name}`);
// SCREENSHOTS=<dir> saves pictures of the main screens for a visual check.
const shot = async (name) => { if (process.env.SCREENSHOTS) await page.screenshot({ path: join(process.env.SCREENSHOTS, `${name}.png`) }); };
try {
  await page.goto(`${origin}/index.html`);
  step("sign-in screen");
  await page.getByRole("heading", { name: "Catalog Dashboard" }).waitFor();
  await page.getByText("Stored in this browser, unencrypted.").waitFor();
  await shot("1-sign-in");
  await page.getByLabel("Personal access token").fill(TOKEN);
  await page.getByRole("button", { name: "Sign in" }).click();

  step("greeting and app list");
  await page.getByRole("heading", { name: "Hello, Israel 👋" }).waitFor();
  assert.equal(await page.evaluate(() => localStorage.getItem("iam.dashboard.token")), TOKEN);
  assert.equal(await page.locator("article.app-card").count(), 15);
  assert.equal(await page.evaluate(() => window.__xss), undefined, "metadata must never run as HTML");
  await page.getByText('<img src=x onerror="window.__xss=1">SeretGram').first().waitFor();

  await shot("2-apps");
  step("add a custom app");
  await page.getByRole("button", { name: "Add app" }).click();
  await page.getByRole("button", { name: "Custom (manual)" }).click();
  await page.getByLabel("Name *").fill("Test App");
  assert.equal(await page.locator("dialog input.mono").inputValue(), "test-app");
  await page.getByLabel("Release strategy").selectOption("direct-apk");
  await page.getByLabel("APK URL *").fill("javascript:alert(1)");
  assert.equal(await page.locator("dialog .dlg-foot .btn.primary").isDisabled(), true, "invalid URL blocks saving");
  await page.getByLabel("APK URL *").fill("https://example.com/test.apk");
  await shot("3-editor");
  await page.locator("dialog .dlg-foot .btn.primary").click();
  await page.getByText("1 unpublished change").waitFor();

  step("draft survives a reload");
  await page.reload();
  await page.getByText("1 unpublished change").waitFor();
  assert.equal(await page.locator("article.app-card").count(), 16);

  step("publish: conflict, merge, publish again");
  await page.getByRole("button", { name: "Review & publish" }).click();
  await page.getByText("The catalog is valid.").waitFor();
  await shot("4-review");
  await page.getByRole("button", { name: "Publish", exact: true }).click();
  await page.getByRole("heading", { name: "Review & publish" }).waitFor();
  await page.getByRole("button", { name: "Publish", exact: true }).click();
  await page.getByText("Everything resolved.").waitFor({ timeout: 30000 });
  await page.getByText("Phones see it on their next refresh.", { exact: false }).waitFor();
  assert.equal(puts.length, 2);
  const committed = JSON.parse(Buffer.from(puts[1].content, "base64").toString("utf8"));
  assert.ok(committed.apps.some((a) => a.id === "test-app" && a.release.url === "https://example.com/test.apk"));
  assert.equal(committed.apps[0].tagline.en, "Changed elsewhere", "the other change was kept");
  assert.match(puts[1].message, /^Catalog: add test-app/);
  assert.equal(await page.evaluate(() => localStorage.getItem("iam.dashboard.draft")), null, "draft cleared after publishing");

  await shot("5-published");
  step("sign out deletes the token");
  await page.keyboard.press("Escape");
  await page.locator("button.hello").click();
  await page.getByRole("menuitem", { name: "Sign out and delete token" }).click();
  await page.getByRole("button", { name: "Sign out and delete token" }).click();
  await page.getByLabel("Personal access token").waitFor();
  assert.equal(await page.evaluate(() => localStorage.getItem("iam.dashboard.token")), null);

  step("narrow screen has no horizontal scroll");
  await page.setViewportSize({ width: 375, height: 800 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);

  assert.deepEqual(tokenLeaks, []);
  const relevant = consoleErrors.filter((e) => !/Failed to load resource|404|ERR_/.test(e));
  assert.deepEqual(relevant, [], `console errors: ${relevant.join("\n")}`);
  console.log("dashboard e2e: all checks passed");
} catch (e) {
  await page.screenshot({ path: join(ROOT, "tests/dashboard-e2e/failure.png"), fullPage: true }).catch(() => {});
  console.error(e);
  console.error("console:", consoleErrors.join("\n"));
  process.exitCode = 1;
} finally {
  await browser.close();
  server.close();
}
