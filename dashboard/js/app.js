// Catalog Dashboard: manage the Israel App Manager catalog from the browser.
//
// How it works: the catalog is the file catalog/catalog.json in the repository. This page reads
// it with the GitHub REST API, lets the owner edit apps and categories locally (with a draft
// saved in this browser), and publishes by committing the file with the SHA it was loaded at,
// so a change made elsewhere is never overwritten silently. The Catalog workflow then validates
// it and publishes it to the `catalog` release; this page follows that and shows the outcome.

import { BRANCH, CATALOG_PATH, FULL_REPO, LINKS, POLL_INTERVAL_MS, PUBLISH_TIMEOUT_MS } from "./config.js";
import { h, clear, icon, safeUrl, timeAgo } from "./dom.js";
import { tokenStore, draftStore, tokenKind } from "./storage.js";
import { GitHubClient, GitHubError } from "./github.js";
import { previewRepository } from "./preview.js";
import {
  APP_TYPES, DEFAULT_CATEGORY, RELEASE_STRATEGIES, ID_PATTERN, LIMITS,
  validateCatalog, localize, normalizeLocalized, isHttpsUrl,
} from "./schema.js";
import {
  clone, deepEqual, diffCatalogs, mergeCatalogs, pruneEmpty, removeCategory, serializeCatalog,
  summarizeDiff, uniqueId, appsInCategory,
} from "./catalog-ops.js";

const root = document.getElementById("root");
const toasts = document.getElementById("toasts");

const state = {
  client: null,
  user: null,
  access: null,
  authProblem: null,
  base: null,
  baseSha: null,
  working: null,
  loadError: null,
  releaseInfo: null,
  published: new Map(),
  previews: new Map(),
  tab: "apps",
  filters: { q: "", category: "", type: "", status: "" },
  publishing: null,
  menuOpen: false,
};

// ------------------------------------------------------------------ utilities
function toast(message, kind = "info") {
  const el = h("div", { class: ["toast", kind === "error" && "error"], role: kind === "error" ? "alert" : "status" }, message);
  toasts.append(el);
  setTimeout(() => el.remove(), kind === "error" ? 8000 : 4500);
}

function errorText(e) {
  if (e instanceof GitHubError) return e.hint ? `${e.message} ${e.hint}` : e.message;
  return e?.message ?? String(e);
}

function handleError(e, context) {
  if (e instanceof GitHubError && e.auth) {
    state.authProblem = errorText(e);
    render();
  }
  toast(context ? `${context}: ${errorText(e)}` : errorText(e), "error");
}

function firstName(user) {
  const name = (user?.name ?? "").trim();
  return name ? name.split(/\s+/)[0] : user?.login ?? "there";
}

function diff() {
  return state.base && state.working ? diffCatalogs(state.base, state.working) : { count: 0, apps: { added: [], removed: [], changed: [] }, categories: { added: [], removed: [], changed: [] }, meta: [] };
}

function saveDraft() {
  if (!state.working) return;
  if (deepEqual(state.base, state.working)) draftStore.clear();
  else draftStore.set({ baseSha: state.baseSha, base: state.base, working: state.working });
}

function mutate(change) {
  change(state.working);
  saveDraft();
  render();
}

function categoryName(id) {
  const category = state.working?.categories?.find((c) => c.id === id);
  return category ? localize(category.name, "en") || id : id;
}

function appCategories(app) {
  return Array.isArray(app.categories) && app.categories.length ? app.categories : [DEFAULT_CATEGORY];
}

function releaseSummary(app) {
  if (app.type === "link") {
    const repo = app.release?.repository ?? app.repository;
    return `GitHub releases · ${repo}`;
  }
  const r = app.release;
  if (!r) return "No release: listed without Install";
  if (r.strategy === "github-latest") return `GitHub releases · ${r.repository}`;
  if (r.strategy === "direct-apk") return "Direct APK link";
  if (r.strategy === "external-json") return "External release JSON";
  return "Unknown release strategy";
}

function appIcon(app, size = 56) {
  const published = state.published.get(app.id);
  const preview = app.type === "link" ? state.previews.get(app.repository)?.resolved?.icon : null;
  const src = safeUrl(app.icon) || safeUrl(preview) || safeUrl(published?.icon);
  const letter = (app.name || app.id || "?").trim().charAt(0).toUpperCase();
  if (!src) return h("div", { class: "app-icon", style: `width:${size}px;height:${size}px`, "aria-hidden": "true" }, letter);
  const img = h("img", { class: "app-icon", src, alt: "", width: size, height: size, loading: "lazy", referrerpolicy: "no-referrer" });
  img.addEventListener("error", () => img.replaceWith(h("div", { class: "app-icon", "aria-hidden": "true" }, letter)), { once: true });
  return img;
}

// ------------------------------------------------------------------ dialogs
function openDialog({ title, body, footer = [], wide = false, onClose, label }) {
  const dlg = h("dialog", { class: wide ? "wide" : "", "aria-label": label ?? title });
  const close = () => {
    dlg.close();
  };
  dlg.append(
    h("div", { class: "dlg" },
      h("div", { class: "dlg-head" }, h("h2", {}, title), h("button", { class: "btn ghost icon-only", "aria-label": "Close", onclick: close }, icon("x"))),
      h("div", { class: "dlg-body" }, body),
      footer.length ? h("div", { class: "dlg-foot" }, footer) : null,
    ),
  );
  dlg.addEventListener("close", () => {
    dlg.remove();
    onClose?.();
  });
  document.body.append(dlg);
  dlg.showModal();
  return { dlg, close };
}

function confirmDialog({ title, message, confirm = "Confirm", danger = false }) {
  return new Promise((resolve) => {
    let answered = false;
    const { close } = openDialog({
      title,
      body: h("div", { class: "stack" }, Array.isArray(message) ? message : h("p", {}, message)),
      footer: [
        h("button", { class: "btn", onclick: () => close() }, "Cancel"),
        h("button", { class: ["btn", danger ? "danger solid" : "primary"], onclick: () => { answered = true; close(); } }, confirm),
      ],
      onClose: () => resolve(answered),
    });
  });
}

function helpDialog() {
  const step = (...children) => h("li", {}, ...children);
  openDialog({
    title: "How to get started?",
    wide: true,
    body: h("div", { class: "help" },
      h("p", {}, "This dashboard edits the list of apps that Israel App Manager shows. Changes are committed to ",
        h("code", {}, CATALOG_PATH), " in ", h("code", {}, FULL_REPO), ", checked by GitHub Actions and published to the ",
        h("a", { href: LINKS.release, target: "_blank" }, "catalog release"), ". Phones pick them up on their next refresh: no new app build is needed."),
      h("h3", {}, "1. Create a token (once)"),
      h("ol", {},
        step("Open GitHub > Settings > Developer settings > Personal access tokens > ", h("a", { href: LINKS.newToken, target: "_blank" }, "Fine-grained tokens"), " > Generate new token."),
        step("Token name: “Catalog dashboard”. Resource owner: your account (", h("code", {}, "il90il90"), ")."),
        step("Expiration: choose ", h("b", {}, "No expiration"), " if GitHub offers it for your account; otherwise pick the longest period allowed and set a reminder to renew it."),
        step("Repository access: ", h("b", {}, "Only select repositories"), " > ", h("code", {}, FULL_REPO), "."),
        step("Permissions > Repository permissions > ", h("b", {}, "Contents: Read and write"), ". Leave everything else as “No access” (Metadata: Read-only is added automatically)."),
        step("Generate the token, copy it (it starts with ", h("code", {}, "github_pat_"), ") and paste it here."),
      ),
      h("h3", {}, "2. Sign in"),
      h("p", {}, "The token is saved in this browser's localStorage, unencrypted, so you stay signed in. Only use the dashboard on your own device and browser profile. Sign out (account menu) deletes it from this browser; revoke it on GitHub if a device is lost."),
      h("h3", {}, "3. Manage apps and categories"),
      h("ol", {},
        step(h("b", {}, "Link"), " entries point to a GitHub repository. Its ", h("code", {}, "app-manager.json"), " supplies the name, developer credit, descriptions, icon and screenshots; the newest GitHub Release supplies the APK and version. Use “Fetch preview” to check it."),
        step(h("b", {}, "Custom"), " entries are written by hand, with a release strategy: GitHub latest release, a direct APK link, or an external JSON file. An app without a release is listed but cannot be installed."),
        step("Categories: every app can be in several. Apps without a category are listed under “Other”."),
        step("Your edits are kept as a draft in this browser until you publish or discard them."),
      ),
      h("h3", {}, "4. Publish"),
      h("p", {}, "“Review & publish” shows every change and validates the catalog. Publishing commits the file; if someone changed it meanwhile you are asked to merge instead of overwriting. The dashboard then follows the Catalog workflow and shows success or the exact errors. If validation fails on GitHub, the previous catalog stays published."),
      h("h3", {}, "5. Roll back"),
      h("p", {}, "History > “Load this version” restores any earlier catalog as a draft for you to publish. For an emergency, Actions > Catalog > Run workflow > rollback swaps back to the previous published file immediately."),
      h("h3", {}, "More"),
      h("p", {}, h("a", { href: LINKS.ownerGuide, target: "_blank" }, "Catalog management guide"), " · ",
        h("a", { href: LINKS.developerGuide, target: "_blank" }, "Guide for app developers"), " · ",
        h("a", { href: LINKS.telegram, target: "_blank" }, "Contact the developer on Telegram")),
    ),
    footer: [h("a", { class: "btn telegram", href: LINKS.telegram, target: "_blank" }, icon("send"), "Contact Developer")],
  });
}

// ------------------------------------------------------------------ sign-in
function renderSignIn({ error = null, busy = false, replacing = false } = {}) {
  clear(root);
  let input;
  const submit = async (event) => {
    event.preventDefault();
    const token = input.value.trim();
    if (!token) return;
    renderSignIn({ busy: true, replacing });
    try {
      await signIn(token);
    } catch (e) {
      renderSignIn({ error: errorText(e), replacing });
    }
  };
  input = h("input", { type: "password", id: "token", autocomplete: "off", spellcheck: "false", placeholder: "github_pat_…", required: true, "aria-describedby": "token-help" });
  const show = h("button", { type: "button", class: "btn", onclick: () => { input.type = input.type === "password" ? "text" : "password"; } }, icon("eye"), h("span", { class: "sr-only" }, "Show or hide the token"));
  root.append(
    h("div", { class: "signin" },
      h("form", { class: "card", onsubmit: submit },
        h("img", { class: "logo", src: new URL("favicon.svg", document.baseURI).href, alt: "" }),
        h("div", {}, h("h1", {}, replacing ? "Replace token" : "Catalog Dashboard"),
          h("p", { class: "muted" }, "Manage the apps in Israel App Manager. Sign in with a GitHub fine-grained personal access token for ", h("code", {}, FULL_REPO), ".")),
        error ? h("div", { class: "banner danger", role: "alert" }, icon("alert"), h("div", { class: "grow" }, error)) : null,
        h("label", { class: "field", for: "token" }, "Personal access token",
          h("div", { class: "input-with-button" }, input, show),
          h("span", { class: "hint", id: "token-help" }, "Fine-grained, only this repository, Contents: Read and write.")),
        h("div", { class: "banner warning" }, icon("alert"), h("div", { class: "grow small" },
          h("b", {}, "Stored in this browser, unencrypted. "),
          "The token is saved in localStorage so you stay signed in, without any extra password. Anyone with access to this browser profile, or any script running on this page, could read it. Use it only on your own device, keep its permissions minimal, and sign out or revoke it on GitHub if the device is shared or lost. This page itself is public; only the token grants write access.")),
        h("div", { class: "row" },
          h("button", { class: "btn primary", type: "submit", disabled: busy }, busy ? "Checking…" : replacing ? "Save new token" : "Sign in"),
          h("button", { class: "btn", type: "button", onclick: helpDialog }, icon("help"), "How to get started?"),
          replacing ? h("button", { class: "btn ghost", type: "button", onclick: () => render() }, "Cancel") : null),
        h("div", { class: "row small muted" },
          h("a", { href: LINKS.newToken, target: "_blank" }, "Create a token on GitHub"), "·",
          h("a", { href: LINKS.ownerGuide, target: "_blank" }, "Owner guide"), "·",
          h("a", { href: LINKS.telegram, target: "_blank" }, "Contact Developer")),
      ),
    ),
  );
  if (!busy) input.focus();
}

async function signIn(token) {
  const kind = tokenKind(token);
  if (kind === "unknown") throw new Error("That does not look like a GitHub token (fine-grained tokens start with github_pat_).");
  const client = new GitHubClient(token);
  const user = await client.getUser();
  let access;
  try {
    access = await client.getRepoAccess();
  } catch (e) {
    if (e instanceof GitHubError && (e.status === 404 || e.status === 403)) {
      throw new Error(`This token cannot see ${FULL_REPO}. Edit the token on GitHub: Repository access > Only select repositories > ${FULL_REPO}.`);
    }
    throw e;
  }
  await client.getCatalogFile(); // proves "Contents: Read"
  tokenStore.set(token);
  Object.assign(state, { client, user, access: { ...access, kind }, authProblem: null });
  if (kind === "classic") toast("A classic token works, but a fine-grained token limited to this repository is safer.");
  await loadEverything();
}

function signOut() {
  tokenStore.clear();
  Object.assign(state, { client: null, user: null, access: null, authProblem: null, menuOpen: false });
  renderSignIn();
  toast("Signed out. The token was deleted from this browser.");
}

// ------------------------------------------------------------------ loading
async function loadEverything() {
  try {
    const file = await state.client.getCatalogFile();
    if (file.parseError) {
      state.loadError = `catalog/catalog.json on GitHub is not valid JSON (${file.parseError}). Fix it on GitHub or load an earlier version from History.`;
      state.base = null;
      state.working = null;
      state.baseSha = file.sha;
    } else {
      state.loadError = null;
      state.base = file.catalog;
      state.baseSha = file.sha;
      restoreDraft(file);
    }
  } catch (e) {
    state.loadError = `The catalog could not be loaded: ${errorText(e)}`;
    if (e instanceof GitHubError && e.auth) state.authProblem = errorText(e);
  }
  render();
  loadReleaseStatus();
  loadPublished();
}

function restoreDraft(file) {
  const draft = draftStore.get();
  if (!draft) {
    state.working = clone(file.catalog);
    return;
  }
  if (draft.baseSha === file.sha) {
    state.working = draft.working;
    if (!deepEqual(draft.working, file.catalog)) toast("Your unpublished changes were restored.");
    return;
  }
  const { merged, conflicts } = mergeCatalogs(draft.base, draft.working, file.catalog);
  state.working = merged;
  saveDraft();
  if (conflicts.length) {
    toast("The catalog changed on GitHub since your draft. Some of your changes conflict: review them.", "error");
    setTimeout(() => conflictDialog(conflicts, draft.working), 300);
  } else {
    toast("The catalog changed on GitHub since your draft; your changes were merged on top.");
  }
}

async function loadReleaseStatus() {
  if (!state.client) return;
  try {
    state.releaseInfo = (await state.client.getCatalogRelease()) ?? { release: null, status: null };
  } catch (e) {
    state.releaseInfo = { error: errorText(e) };
  }
  if (!state.publishing) renderStatusStrip();
}

/** What phones see now: the mirror of the published catalog (readable from a browser). */
async function loadPublished() {
  try {
    const response = await fetch(`https://raw.githubusercontent.com/${FULL_REPO}/refs/heads/catalog/catalog.json`, { cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" });
    if (!response.ok) return;
    const catalog = await response.json();
    const base = safeUrl(catalog.baseUrl) ?? "";
    state.published = new Map(
      (catalog.apps ?? []).map((app) => [app.id, {
        icon: app.icon ? (app.icon.startsWith("https://") ? app.icon : base + app.icon) : null,
        version: app.variants?.[0]?.versionName ?? null,
        tag: app.release?.tag ?? null,
        apks: app.variants?.length ?? 0,
      }]),
    );
    state.publishedAt = catalog.generatedAt;
    if (state.tab === "apps" && !document.querySelector("dialog")) render();
  } catch {
    /* only decoration */
  }
}

// ------------------------------------------------------------------ main view
function render() {
  if (!state.client) {
    renderSignIn();
    return;
  }
  clear(root);
  root.append(renderTopbar());
  const main = h("main", { class: "page", id: "main" });
  main.append(
    h("div", { class: "page-head" },
      h("div", { style: "flex:1;min-width:240px" },
        h("h1", {}, `Hello, ${firstName(state.user)} 👋`),
        h("p", {}, "Manage the apps and categories of Israel App Manager. Publishing needs no new app build.")),
    ),
  );
  if (state.authProblem) {
    main.append(h("div", { class: "banner danger", role: "alert", style: "margin-bottom:16px" }, icon("alert"),
      h("div", { class: "grow" }, h("b", {}, "Your token no longer works. "), state.authProblem),
      h("button", { class: "btn small", onclick: () => renderSignIn({ replacing: true }) }, "Replace token")));
  }
  main.append(h("div", { id: "status-strip" }));
  if (state.loadError) {
    main.append(h("div", { class: "banner danger", role: "alert" }, icon("alert"), h("div", { class: "grow" }, state.loadError),
      h("button", { class: "btn small", onclick: loadEverything }, icon("refresh"), "Retry")));
  }
  const d = diff();
  const tabs = [["apps", "Apps", state.working?.apps?.length], ["categories", "Categories", state.working?.categories?.length], ["settings", "Settings"], ["history", "History"]];
  main.append(h("div", { class: "tabs", role: "tablist", "aria-label": "Sections" },
    tabs.map(([id, label, count]) => h("button", { role: "tab", "aria-selected": String(state.tab === id), onclick: () => { state.tab = id; render(); } },
      label, count !== undefined ? h("span", { class: "count" }, String(count)) : null))));
  const content = h("section", { id: "tab-content" });
  main.append(content);
  if (state.working) {
    if (state.tab === "apps") renderApps(content, d);
    else if (state.tab === "categories") renderCategories(content);
    else if (state.tab === "settings") renderSettings(content);
  }
  if (state.tab === "history") renderHistory(content);
  root.append(main);
  if (d.count && state.working) root.append(renderChangesBar(d));
  renderStatusStrip();
}

function renderTopbar() {
  const avatar = safeUrl(state.user?.avatar_url)
    ? h("img", { src: state.user.avatar_url, alt: "", width: 30, height: 30, referrerpolicy: "no-referrer" })
    : h("span", { class: "avatar-fallback" }, firstName(state.user).charAt(0));
  const menu = state.menuOpen ? renderAccountMenu() : null;
  return h("header", { class: "topbar" },
    h("div", { class: "brand" }, h("img", { src: new URL("favicon.svg", document.baseURI).href, alt: "" }),
      h("div", {}, "Catalog Dashboard", h("small", {}, "Israel App Manager"))),
    h("div", { class: "spacer" }),
    h("div", { class: "top-actions" },
      h("button", { class: "btn ghost", onclick: helpDialog }, icon("help"), h("span", { class: "hide-sm" }, "How to get started?")),
      h("a", { class: "btn telegram", href: LINKS.telegram, target: "_blank", title: "Message the developer on Telegram" }, icon("send"), h("span", { class: "hide-sm" }, "Contact Developer")),
      h("div", { class: "menu-wrap" },
        h("button", { class: "hello", "aria-haspopup": "menu", "aria-expanded": String(state.menuOpen), onclick: (e) => { e.stopPropagation(); state.menuOpen = !state.menuOpen; render(); } },
          h("span", {}, `Hello, ${firstName(state.user)} 👋`), avatar),
        menu)));
}

function renderAccountMenu() {
  const a = state.access ?? {};
  return h("div", { class: "menu card", role: "menu", onclick: (e) => e.stopPropagation() },
    h("div", { class: "who" },
      h("div", {}, h("b", {}, state.user?.name || state.user?.login), " ", h("span", { class: "muted" }, `@${state.user?.login}`)),
      h("div", { class: "small muted" }, `${a.kind === "fine-grained" ? "Fine-grained" : a.kind === "classic" ? "Classic" : "Unknown"} token · saved in this browser (unencrypted)`),
      h("div", { class: "small muted" }, a.expiresAt ? `Expires ${a.expiresAt}` : "Expiration: not reported to browsers (see GitHub settings)"),
      h("div", { class: "small" }, a.canPush ? h("span", { class: "chip ok" }, "Write access to the repository") : h("span", { class: "chip warn" }, "Write access not confirmed"))),
    h("button", { role: "menuitem", onclick: checkAccess }, icon("key"), "Check token access"),
    h("button", { role: "menuitem", onclick: () => { state.menuOpen = false; renderSignIn({ replacing: true }); } }, icon("refresh"), "Replace token"),
    h("a", { role: "menuitem", href: LINKS.tokens, target: "_blank" }, icon("external"), "Manage tokens on GitHub"),
    draftStore.get() ? h("button", { role: "menuitem", onclick: discardDraft }, icon("undo"), "Discard unpublished changes") : null,
    h("button", { role: "menuitem", class: "danger", onclick: async () => {
      state.menuOpen = false;
      const yes = await confirmDialog({ title: "Sign out?", message: "This deletes the token from this browser. Your unpublished draft stays until you discard it. To stop the token working everywhere, revoke it on GitHub.", confirm: "Sign out and delete token", danger: true });
      if (yes) signOut(); else render();
    } }, icon("logout"), "Sign out and delete token"));
}

async function checkAccess() {
  state.menuOpen = false;
  try {
    const access = await state.client.getRepoAccess();
    state.access = { ...state.access, ...access };
    state.authProblem = null;
    toast(access.canPush ? "The token can read the repository; GitHub reports write access for your account." : "The token can read the repository, but write access was not confirmed. Publishing will tell for sure.");
  } catch (e) {
    handleError(e, "Token check");
  }
  render();
}

document.addEventListener("click", () => {
  if (state.menuOpen) {
    state.menuOpen = false;
    render();
  }
});

function renderStatusStrip() {
  const host = document.getElementById("status-strip");
  if (!host) return;
  clear(host);
  const info = state.releaseInfo;
  let dot = "dot";
  let text = "Checking the published catalog…";
  let details = null;
  if (info?.error) {
    dot = "dot bad";
    text = `Published status unavailable: ${info.error}`;
  } else if (info && !info.release) {
    text = "Not published yet: the first change you publish creates the catalog release.";
  } else if (info?.status) {
    const s = info.status;
    const when = s.at ? timeAgo(s.at) : "";
    if (s.state === "failed") {
      dot = "dot bad";
      text = `Last catalog run failed ${when}; the previous catalog is still published.`;
      details = s.errors?.length ? h("ul", { class: "problems" }, s.errors.slice(0, 6).map((e) => h("li", { class: "error" }, e))) : null;
    } else if (s.state === "rolled-back") {
      dot = "dot busy";
      text = `Rolled back ${when}; pinned until the next catalog change.`;
    } else {
      dot = "dot ok";
      text = `Published and healthy · checked ${when}${s.apps != null ? ` · ${s.apps} apps` : ""}`;
    }
  } else if (info?.release) {
    dot = "dot ok";
    text = "Published.";
  }
  host.append(h("div", { class: "card status-strip" },
    h("span", { class: dot }), h("div", { style: "flex:1;min-width:220px" }, text, details),
    info?.status?.runUrl ? h("a", { href: info.status.runUrl, target: "_blank", class: "small" }, "Workflow run") : null,
    h("a", { href: LINKS.release, target: "_blank", class: "small" }, "Catalog release"),
    h("button", { class: "btn small ghost", onclick: () => { loadReleaseStatus(); loadPublished(); } }, icon("refresh"), "Refresh")));
}

function renderChangesBar(d) {
  return h("div", { class: "changes-bar", role: "region", "aria-label": "Unpublished changes" },
    h("div", { class: "grow" }, `${d.count} unpublished change${d.count === 1 ? "" : "s"}`, h("div", { class: "small", style: "opacity:.75;font-weight:500" }, "Saved as a draft in this browser")),
    h("button", { class: "btn ghost small", onclick: discardDraft }, "Discard"),
    h("button", { class: "btn primary", onclick: publishDialog }, icon("send"), "Review & publish"));
}

async function discardDraft() {
  state.menuOpen = false;
  const yes = await confirmDialog({ title: "Discard unpublished changes?", message: "Your draft goes back to the catalog as it is on GitHub. This cannot be undone.", confirm: "Discard changes", danger: true });
  if (!yes) return render();
  draftStore.clear();
  state.working = clone(state.base);
  render();
  toast("Changes discarded.");
}

// ------------------------------------------------------------------ apps tab
function renderApps(host, d) {
  const f = state.filters;
  const cats = state.working.categories ?? [];
  const search = h("input", { type: "search", placeholder: "Search name, id, developer or repository", value: f.q, "aria-label": "Search apps",
    oninput: (e) => { f.q = e.target.value; renderList(); } });
  const select = (key, label, options) => h("select", { "aria-label": label, onchange: (e) => { f[key] = e.target.value; renderList(); } },
    options.map(([value, text]) => h("option", { value, selected: f[key] === value }, text)));
  host.append(h("div", { class: "toolbar" },
    h("div", { class: "search" }, icon("search"), search),
    select("category", "Category", [["", "All categories"], ...cats.map((c) => [c.id, localize(c.name, "en") || c.id])]),
    select("type", "Type", [["", "All types"], ["link", "Link"], ["custom", "Custom"]]),
    select("status", "Status", [["", "Any status"], ["changed", "Changed (unpublished)"], ["no-release", "No release"], ["hidden", "Hidden"], ["featured", "Featured"], ["premium", "Premium"]]),
    h("button", { class: "btn primary", onclick: () => appEditor(null) }, icon("plus"), "Add app")));
  const list = h("div", { class: "app-list" });
  host.append(list);

  function renderList() {
    clear(list);
    const q = f.q.trim().toLowerCase();
    const added = new Set(d.apps.added);
    const changed = new Set(d.apps.changed);
    const apps = state.working.apps.filter((app) => {
      if (q && ![app.id, app.name, app.by, app.repository, app.release?.repository].some((v) => typeof v === "string" && v.toLowerCase().includes(q))) return false;
      if (f.category && !appCategories(app).includes(f.category)) return false;
      if (f.type && app.type !== f.type) return false;
      if (f.status === "changed" && !added.has(app.id) && !changed.has(app.id)) return false;
      if (f.status === "no-release" && !(app.type === "custom" && !app.release)) return false;
      if (f.status === "hidden" && !app.hidden) return false;
      if (f.status === "featured" && !app.featured) return false;
      if (f.status === "premium" && !app.license) return false;
      return true;
    });
    if (!apps.length) {
      list.append(h("div", { class: "card empty" }, state.working.apps.length ? "No app matches these filters." : "No apps yet. Use “Add app” to add the first one."));
      return;
    }
    for (const app of apps) list.append(appCard(app, added.has(app.id), changed.has(app.id)));
  }
  renderList();
}

function appCard(app, isNew, isChanged) {
  const index = state.working.apps.findIndex((a) => a.id === app.id);
  const published = state.published.get(app.id);
  const move = (delta) => mutate((c) => {
    const [item] = c.apps.splice(index, 1);
    c.apps.splice(index + delta, 0, item);
  });
  const tagline = localize(app.tagline, "en");
  return h("article", { class: ["card", "app-card", isNew && "added", isChanged && "changed", app.hidden && "hidden-app"], "aria-label": app.name || app.id },
    appIcon(app),
    h("div", { style: "min-width:0" },
      h("h3", {}, app.name || (app.type === "link" ? app.repository : app.id),
        h("span", { class: ["chip", `type-${app.type}`] }, app.type === "link" ? "Link" : "Custom"),
        isNew ? h("span", { class: "chip ok" }, "New") : isChanged ? h("span", { class: "chip warn" }, "Edited") : null),
      h("div", { class: "meta" }, h("span", { class: "mono" }, app.id), app.by ? ` · by ${app.by}` : ""),
      tagline ? h("p", { class: "tagline" }, tagline) : null,
      h("div", { class: "chips" },
        appCategories(app).map((c) => h("span", { class: "chip" }, categoryName(c))),
        app.featured ? h("span", { class: "chip ok" }, "Featured") : null,
        app.license ? h("span", { class: "chip warn" }, "Premium") : null,
        app.hidden ? h("span", { class: "chip bad" }, "Hidden") : null,
        app.self ? h("span", { class: "chip" }, "This manager") : null),
      h("div", { class: "meta", style: "margin-top:8px" }, releaseSummary(app),
        published ? ` · published ${published.version ?? published.tag ?? ""}${published.apks ? "" : " (no APK)"}` : ""),
      h("div", { class: "actions" },
        h("button", { class: "btn small", onclick: () => appEditor(app.id) }, icon("edit", 16), "Edit"),
        h("button", { class: "btn small ghost icon-only", "aria-label": `Move ${app.id} up`, disabled: index <= 0, onclick: () => move(-1) }, icon("up", 16)),
        h("button", { class: "btn small ghost icon-only", "aria-label": `Move ${app.id} down`, disabled: index >= state.working.apps.length - 1, onclick: () => move(1) }, icon("down", 16)),
        h("button", { class: "btn small ghost danger", onclick: () => deleteApp(app) }, icon("trash", 16), "Delete"))));
}

async function deleteApp(app) {
  const yes = await confirmDialog({
    title: `Delete ${app.name || app.id}?`,
    message: [h("p", {}, "The app disappears from Israel App Manager after you publish. Phones that installed it keep it, but get no more updates through the manager."),
      h("p", { class: "muted small" }, "Tip: “Hidden” removes it from the list but keeps the entry.")],
    confirm: "Delete app",
    danger: true,
  });
  if (yes) mutate((c) => { c.apps = c.apps.filter((a) => a.id !== app.id); });
}

// ------------------------------------------------------------------ app editor
const APP_KEY_ORDER = ["id", "type", "name", "by", "repository", "categories", "featured", "hidden", "license", "self", "website", "storeUrl", "repoUrl", "tagline", "description", "icon", "screenshots", "accent", "iconStyle", "release"];

function orderedApp(app) {
  const out = {};
  for (const key of APP_KEY_ORDER) if (app[key] !== undefined) out[key] = app[key];
  for (const key of Object.keys(app)) if (!(key in out)) out[key] = app[key];
  return out;
}

function appEditor(appId) {
  const existing = appId ? state.working.apps.find((a) => a.id === appId) : null;
  const model = existing ? clone(existing) : { id: "", type: "link", categories: [] };
  for (const key of ["tagline", "description"]) model[key] = normalizeLocalized(model[key]) ?? {};
  if (model.license === true) model.license = { };
  let idTouched = Boolean(existing);
  const problemsBox = h("div");
  const previewBox = h("div", { class: "preview" });
  const typeBox = h("div", { class: "stack" });

  const set = (path, value) => {
    const keys = path.split(".");
    let obj = model;
    for (const k of keys.slice(0, -1)) obj = obj[k] ??= {};
    if (value === "" || value === undefined) delete obj[keys.at(-1)];
    else obj[keys.at(-1)] = value;
    refresh();
  };
  const get = (path) => path.split(".").reduce((o, k) => (o == null ? undefined : o[k]), model);
  const text = (path, label, { hint, placeholder, dir, type = "text", readOnly = false, required = false, max } = {}) =>
    h("label", { class: "field" }, required ? `${label} *` : label,
      h("input", { type, value: get(path) ?? "", placeholder, dir, readOnly, maxlength: max, oninput: (e) => set(path, e.target.value.trim() === "" ? "" : e.target.value) }),
      hint ? h("span", { class: "hint" }, hint) : null);
  const area = (path, label, { dir, hint, max } = {}) =>
    h("label", { class: "field" }, label, h("textarea", { dir, maxlength: max, oninput: (e) => set(path, e.target.value) }, get(path) ?? ""), hint ? h("span", { class: "hint" }, hint) : null);
  const localized = (key, label, max, multiline = false) => h("div", { class: "grid-2" },
    multiline ? area(`${key}.en`, `${label} (English)`, { max }) : text(`${key}.en`, `${label} (English)`, { max }),
    multiline ? area(`${key}.he`, `${label} (Hebrew, optional)`, { dir: "rtl", max }) : text(`${key}.he`, `${label} (Hebrew, optional)`, { dir: "rtl", max }));
  const flag = (key, label, hint) => h("label", { class: "check", title: hint }, h("input", { type: "checkbox", checked: Boolean(model[key]), onchange: (e) => set(key, e.target.checked || undefined) }), label);
  const screenshots = () => h("label", { class: "field" }, "Screenshots (optional)",
    h("textarea", { placeholder: "https://… one image link per line", oninput: (e) => set("screenshots", e.target.value.split("\n").map((s) => s.trim()).filter(Boolean)) }, (model.screenshots ?? []).join("\n")),
    h("span", { class: "hint" }, `Up to ${LIMITS.screenshots} https:// images. Missing media is simply not shown.`));

  const nameInput = text("name", "Name", { max: LIMITS.name, required: true });
  nameInput.querySelector("input").addEventListener("input", (e) => {
    if (!idTouched && !existing) {
      const taken = new Set(state.working.apps.map((a) => a.id));
      model.id = uniqueId(e.target.value, taken);
      idField.querySelector("input").value = model.id;
      refresh();
    }
  });
  const idField = h("label", { class: "field" }, existing ? "App ID" : "App ID *",
    h("input", { type: "text", value: model.id, readOnly: Boolean(existing), class: "mono", placeholder: "my-app", oninput: (e) => { idTouched = true; set("id", e.target.value.trim()); } }),
    h("span", { class: "hint" }, existing ? "IDs are stable: phones use them to recognise the app. Delete and re-add to change it." : "Lowercase letters, digits and dashes. Cannot be changed later."));

  const categoryPicker = h("fieldset", {}, h("legend", {}, "Categories"),
    h("div", { class: "chips" }, (state.working.categories ?? []).map((c) => h("label", { class: "check chip", style: "padding:4px 10px" },
      h("input", { type: "checkbox", checked: (model.categories ?? []).includes(c.id), onchange: (e) => {
        const list = new Set(model.categories ?? []);
        if (e.target.checked) list.add(c.id); else list.delete(c.id);
        set("categories", (state.working.categories ?? []).map((x) => x.id).filter((id) => list.has(id)));
      } }), localize(c.name, "en") || c.id))),
    h("span", { class: "hint" }, "None selected means “Other”. Manage the list in the Categories tab."));

  function typeSection() {
    clear(typeBox);
    if (model.type === "link") {
      typeBox.append(
        h("fieldset", {}, h("legend", {}, "Repository"),
          h("div", { class: "input-with-button" },
            h("input", { type: "text", value: model.repository ?? "", placeholder: "owner/repository", "aria-label": "GitHub repository", oninput: (e) => set("repository", e.target.value.trim()) }),
            h("button", { class: "btn", type: "button", onclick: fetchPreview }, icon("refresh"), "Fetch preview")),
          h("span", { class: "hint" }, "A public GitHub repository with an app-manager.json and GitHub Releases that carry the APK. Its metadata is read automatically on every publish.")),
        h("details", { class: "more" }, h("summary", {}, "Override the repository's metadata (optional)"),
          nameInputFor("Name (overrides app-manager.json)"),
          text("by", "Developer credit (“by”)", { max: LIMITS.by }),
          localized("tagline", "Tagline", LIMITS.tagline),
          localized("description", "Description", LIMITS.description, true),
          text("icon", "Icon URL", { type: "url", placeholder: "https://…" }),
          screenshots()),
        h("details", { class: "more" }, h("summary", {}, "Release filters (optional)"),
          h("div", { class: "grid-2" },
            text("release.repository", "Releases repository", { placeholder: "owner/repository-releases", hint: "If the APKs are published in another repository" }),
            text("release.tag", "Tag pattern", { placeholder: "^v\\d", hint: "Regular expression" }),
            text("release.asset", "APK file pattern", { placeholder: "\\.apk$", hint: "Regular expression" }),
            text("release.exclude", "Exclude files matching", { hint: "Regular expression" })),
          h("label", { class: "check" }, h("input", { type: "checkbox", checked: Boolean(model.release?.prerelease), onchange: (e) => set("release.prerelease", e.target.checked || undefined) }), "Include pre-releases")),
      );
    } else {
      const strategy = model.release?.strategy ?? "";
      const strategySelect = h("select", { "aria-label": "Release strategy", onchange: (e) => {
        const value = e.target.value;
        model.release = value ? { strategy: value } : undefined;
        if (!value) delete model.release;
        typeSection();
        refresh();
      } }, [["", "No release (listed, not installable)"], ["github-latest", "GitHub: newest release"], ["direct-apk", "Direct APK link"], ["external-json", "External JSON file"]]
        .map(([v, t]) => h("option", { value: v, selected: v === strategy }, t)));
      const releaseFields = [];
      if (strategy === "github-latest") {
        releaseFields.push(h("div", { class: "grid-2" },
          text("release.repository", "Repository", { placeholder: "owner/repository", required: true }),
          text("release.tag", "Tag pattern", { placeholder: "^v\\d", hint: "Regular expression, optional" }),
          text("release.asset", "APK file pattern", { placeholder: "\\.apk$", hint: "Regular expression, optional" }),
          text("release.exclude", "Exclude files matching", { hint: "Regular expression, optional" })),
        h("label", { class: "check" }, h("input", { type: "checkbox", checked: Boolean(model.release?.prerelease), onchange: (e) => set("release.prerelease", e.target.checked || undefined) }), "Include pre-releases"));
      } else if (strategy === "direct-apk") {
        releaseFields.push(text("release.url", "APK URL", { type: "url", placeholder: "https://example.com/app.apk", required: true, hint: "Downloaded and checked on every publish; the version is read from the APK." }),
          text("release.versionName", "Version label (optional)", { placeholder: "1.0.0" }));
      } else if (strategy === "external-json") {
        releaseFields.push(text("release.url", "Release JSON URL", { type: "url", placeholder: "https://example.com/latest.json", required: true,
          hint: 'A JSON file such as {"versionName": "1.2.0", "url": "https://…/app.apk", "sha256": "…", "notes": "…"}' }));
      }
      typeBox.append(
        h("fieldset", {}, h("legend", {}, "About the app"),
          nameInputFor("Name"),
          text("by", "Developer credit (“by”)", { max: LIMITS.by, placeholder: state.working.developer || "Israel" }),
          localized("tagline", "Tagline", LIMITS.tagline),
          localized("description", "Description", LIMITS.description, true),
          text("icon", "Icon URL (optional)", { type: "url", placeholder: "https://…", hint: "Without one, the icon is taken from the APK." }),
          screenshots(),
          h("div", { class: "grid-2" }, text("website", "Website (optional)", { type: "url" }), text("storeUrl", "Store page (optional)", { type: "url" }))),
        h("fieldset", {}, h("legend", {}, "Release"), strategySelect, releaseFields));
    }
  }

  function nameInputFor(label) {
    nameInput.firstChild.textContent = model.type === "custom" ? `${label} *` : label;
    return nameInput;
  }

  async function fetchPreview() {
    if (!model.repository) return toast("Enter the repository first.", "error");
    clear(previewBox);
    previewBox.append(h("div", { class: "card pad muted" }, "Looking at the repository…"));
    try {
      const result = await previewRepository(model.repository, model);
      state.previews.set(model.repository, result);
    } catch (e) {
      state.previews.set(model.repository, { error: errorText(e) });
    }
    refresh();
  }

  function candidateCatalog() {
    const app = orderedApp(pruneEmpty(clone(model)));
    if (model.license && typeof model.license === "object") app.license = model.license.contact ? { contact: model.license.contact } : true;
    const catalog = clone(state.working);
    const index = existing ? catalog.apps.findIndex((a) => a.id === existing.id) : -1;
    if (index >= 0) catalog.apps[index] = app; else catalog.apps.push(app);
    return { catalog, app, index: index >= 0 ? index : catalog.apps.length - 1 };
  }

  function refresh() {
    const { catalog, index } = candidateCatalog();
    const result = validateCatalog(catalog);
    const prefix = `apps[${index}]`;
    const mine = (list) => list.filter((p) => p.path === prefix || p.path.startsWith(`${prefix}.`) || (p.path === "apps" && /self/.test(p.message)));
    const errors = mine(result.errors);
    const warnings = mine(result.warnings);
    clear(problemsBox);
    if (errors.length || warnings.length) {
      problemsBox.append(h("ul", { class: "problems" },
        errors.map((p) => h("li", { class: "error" }, icon("x", 16), `${p.path.replace(prefix, "app")}: ${p.message}`)),
        warnings.map((p) => h("li", { class: "warning" }, icon("alert", 16), `${p.path.replace(prefix, "app")}: ${p.message}`))));
    } else {
      problemsBox.append(h("div", { class: "banner success small" }, icon("check", 16), "Looks good."));
    }
    saveButton.disabled = errors.length > 0;
    renderPreview();
  }

  function renderPreview() {
    clear(previewBox);
    const p = model.type === "link" ? state.previews.get(model.repository) : null;
    const r = p?.resolved ?? {};
    const name = model.name || r.name || model.repository || model.id || "New app";
    const tagline = localize(model.tagline, "en") || localize(r.tagline, "en");
    const description = localize(model.description, "en") || localize(r.description, "en");
    const shots = (model.screenshots?.length ? model.screenshots : r.screenshots ?? []).map((u) => safeUrl(u)).filter(Boolean);
    const iconApp = { ...model, name, icon: model.icon || r.icon };
    previewBox.append(h("div", { class: "phone", "aria-label": "Preview" },
      h("div", { class: "small muted" }, "Preview"),
      h("div", { class: "head" }, appIcon(iconApp, 64), h("div", {}, h("h3", {}, name),
        h("div", { class: "muted small" }, [model.by || r.by ? `by ${model.by || r.by}` : null, appCategories(model).map(categoryName).join(", ")].filter(Boolean).join(" · ")))),
      tagline ? h("b", {}, tagline) : h("span", { class: "muted small" }, "No tagline: none is shown."),
      description ? h("p", { class: "small", style: "white-space:pre-wrap" }, description.length > 400 ? `${description.slice(0, 400)}…` : description) : null,
      shots.length ? h("div", { class: "shots" }, shots.map((src) => h("img", { src, alt: "Screenshot", loading: "lazy", referrerpolicy: "no-referrer" }))) : null));
    if (model.type === "link") {
      if (!p) previewBox.append(h("div", { class: "banner info small" }, icon("help", 16), h("div", { class: "grow" }, "Use “Fetch preview” to read the repository's app-manager.json and newest release.")));
      else if (p.error) previewBox.append(h("div", { class: "banner danger small" }, icon("alert", 16), h("div", { class: "grow" }, p.error)));
      else {
        previewBox.append(h("dl", { class: "kv card pad" },
          h("dt", {}, "app-manager.json"), h("dd", {}, p.manifestPath ? (p.validation?.ok ? h("span", { class: "chip ok" }, "valid") : h("span", { class: "chip bad" }, "has errors")) : h("span", { class: "chip warn" }, "not found")),
          h("dt", {}, "Latest release"), h("dd", {}, p.release ? `${p.release.tag_name}${p.release.published_at ? ` · ${timeAgo(p.release.published_at)}` : ""}` : "none"),
          h("dt", {}, "APK files"), h("dd", {}, p.apks?.length ? p.apks.map((a) => a.name).join(", ") : h("span", { class: "chip warn" }, "none: Install disabled"))));
        const issues = [...(p.validation?.errors ?? []).map((e) => ({ kind: "error", text: `app-manager.json ${e.path}: ${e.message}` })),
          ...(p.validation?.warnings ?? []).map((e) => ({ kind: "warning", text: `app-manager.json ${e.path}: ${e.message}` })),
          ...p.problems.map((t) => ({ kind: "warning", text: t }))];
        if (issues.length) previewBox.append(h("ul", { class: "problems" }, issues.map((i) => h("li", { class: i.kind }, icon(i.kind === "error" ? "x" : "alert", 16), i.text))));
      }
    } else {
      const published = state.published.get(model.id);
      if (published) previewBox.append(h("div", { class: "card pad small" }, `Currently published: ${published.version ?? published.tag ?? "?"} · ${published.apks} APK file(s)`));
    }
  }

  const typeSwitch = h("div", { class: "segmented", role: "group", "aria-label": "Entry type" },
    APP_TYPES.map((t) => h("button", { type: "button", "aria-pressed": String(model.type === t), onclick: (e) => {
      if (model.type === t) return;
      model.type = t;
      if (t === "custom") { delete model.repository; if (model.release && !model.release.strategy) model.release = { strategy: "github-latest", ...model.release }; }
      if (t === "link" && model.release) { const { strategy, url, versionName, ...rest } = model.release; model.release = Object.keys(rest).length ? rest : undefined; if (!model.release) delete model.release; }
      for (const b of e.currentTarget.parentElement.children) b.setAttribute("aria-pressed", String(b === e.currentTarget));
      typeSection();
      refresh();
    } }, t === "link" ? "Link (GitHub repository)" : "Custom (manual)")));

  const licenseRow = h("div", { class: "stack" },
    h("label", { class: "check" }, h("input", { type: "checkbox", checked: Boolean(model.license), onchange: (e) => { model.license = e.target.checked ? {} : undefined; if (!e.target.checked) delete model.license; licenseContact.hidden = !e.target.checked; refresh(); } }), "Premium: a license from the developer is required"));
  const licenseContact = h("label", { class: "field", hidden: !model.license }, "License contact (optional)",
    h("input", { type: "url", value: model.license?.contact ?? "", placeholder: "Defaults to the catalog's Telegram contact", oninput: (e) => { model.license = { ...(model.license ?? {}), contact: e.target.value.trim() || undefined }; refresh(); } }));
  licenseRow.append(licenseContact);

  const saveButton = h("button", { class: "btn primary", onclick: () => {
    const { app } = candidateCatalog();
    mutate((c) => {
      const index = existing ? c.apps.findIndex((a) => a.id === existing.id) : -1;
      if (index >= 0) c.apps[index] = app; else c.apps.push(app);
    });
    close();
    toast(existing ? "Saved to your draft." : "Added to your draft. Publish when you are ready.");
  } }, icon("check"), existing ? "Save changes" : "Add app");

  const form = h("div", { class: "stack" },
    h("div", { class: "row" }, typeSwitch),
    h("div", { class: "grid-2" }, idField, h("div", { class: "stack", style: "align-content:end" },
      h("div", { class: "row" }, flag("featured", "Featured"), flag("hidden", "Hidden (keep the entry, do not list it)")))),
    typeBox,
    categoryPicker,
    h("fieldset", {}, h("legend", {}, "Options"), licenseRow, flag("self", "This is Israel App Manager itself")),
    problemsBox);
  const { close } = openDialog({
    title: existing ? `Edit ${existing.name || existing.id}` : "Add app",
    wide: true,
    body: h("div", { class: "editor" }, form, previewBox),
    footer: [h("button", { class: "btn", onclick: () => close() }, "Cancel"), saveButton],
  });
  typeSection();
  refresh();
  if (model.type === "link" && model.repository && !state.previews.has(model.repository)) fetchPreview();
}

// ------------------------------------------------------------------ categories tab
function renderCategories(host) {
  const cats = state.working.categories ?? [];
  const nameInput = (category, lang) => h("input", { type: "text", value: category.name?.[lang] ?? (lang === "en" && typeof category.name === "string" ? category.name : ""), dir: lang === "he" ? "rtl" : undefined, "aria-label": `${category.id} name (${lang})`,
    onchange: (e) => mutate((c) => {
      const target = c.categories.find((x) => x.id === category.id);
      target.name = normalizeLocalized(target.name) ?? {};
      if (e.target.value.trim()) target.name[lang] = e.target.value.trim(); else delete target.name[lang];
    }) });
  const move = (index, delta) => mutate((c) => { const [x] = c.categories.splice(index, 1); c.categories.splice(index + delta, 0, x); });
  const rows = cats.map((category, i) => h("tr", {},
    h("td", { class: "mono" }, category.id),
    h("td", {}, nameInput(category, "en")),
    h("td", {}, nameInput(category, "he")),
    h("td", {}, String(appsInCategory(state.working, category.id).length)),
    h("td", {}, h("div", { class: "row", style: "flex-wrap:nowrap" },
      h("button", { class: "btn small ghost icon-only", "aria-label": `Move ${category.id} up`, disabled: i === 0, onclick: () => move(i, -1) }, icon("up", 16)),
      h("button", { class: "btn small ghost icon-only", "aria-label": `Move ${category.id} down`, disabled: i === cats.length - 1, onclick: () => move(i, 1) }, icon("down", 16)),
      h("button", { class: "btn small ghost danger", disabled: category.id === DEFAULT_CATEGORY, title: category.id === DEFAULT_CATEGORY ? "The default category cannot be removed" : "", onclick: async () => {
        const count = appsInCategory(state.working, category.id).length;
        const yes = await confirmDialog({ title: `Remove “${localize(category.name, "en") || category.id}”?`, message: count ? `${count} app(s) use it. Apps left without a category move to “Other”.` : "No app uses it.", confirm: "Remove category", danger: true });
        if (yes) mutate((c) => Object.assign(c, removeCategory(c, category.id)));
      } }, icon("trash", 16), "Remove")))));
  const en = h("input", { type: "text", placeholder: "English name", "aria-label": "New category English name" });
  const he = h("input", { type: "text", placeholder: "Hebrew name (optional)", dir: "rtl", "aria-label": "New category Hebrew name" });
  host.append(
    h("div", { class: "toolbar" }, h("p", { class: "muted", style: "margin:0;flex:1" }, "The central list of categories. Apps can be in several; apps without one are shown under “Other”. IDs never change once phones have seen them.")),
    h("div", { class: "card table-wrap" }, h("table", { class: "table" },
      h("thead", {}, h("tr", {}, ["ID", "English name", "Hebrew name", "Apps", ""].map((t) => h("th", {}, t)))),
      h("tbody", {}, rows))),
    h("form", { class: "card pad row", style: "margin-top:14px", onsubmit: (e) => {
      e.preventDefault();
      if (!en.value.trim()) return toast("Give the category an English name.", "error");
      const id = uniqueId(en.value, new Set(cats.map((c) => c.id)));
      mutate((c) => c.categories.splice(Math.max(0, c.categories.findIndex((x) => x.id === DEFAULT_CATEGORY)), 0, { id, name: pruneEmpty({ en: en.value.trim(), he: he.value.trim() }) }));
      toast(`Category “${id}” added.`);
    } }, h("div", { style: "flex:1 1 200px" }, en), h("div", { style: "flex:1 1 200px" }, he), h("button", { class: "btn primary", type: "submit" }, icon("plus"), "Add category")));
}

// ------------------------------------------------------------------ settings tab
function renderSettings(host) {
  const c = state.working;
  const field = (label, value, onchange, opts = {}) => h("label", { class: "field" }, label, h("input", { type: opts.type ?? "text", value: value ?? "", dir: opts.dir, placeholder: opts.placeholder, onchange: (e) => mutate((cat) => onchange(cat, e.target.value.trim())) }), opts.hint ? h("span", { class: "hint" }, opts.hint) : null);
  const ann = c.announcement;
  host.append(h("div", { class: "stack", style: "margin-top:16px;max-width:820px" },
    h("div", { class: "card pad stack" }, h("h3", {}, "Catalog"),
      field("Default developer credit", c.developer, (cat, v) => { if (v) cat.developer = v; else delete cat.developer; }, { hint: "Used for apps that do not say who made them." }),
      field("Telegram contact", c.contact?.telegram, (cat, v) => { cat.contact = { ...(cat.contact ?? {}) }; if (v) cat.contact.telegram = v; else delete cat.contact.telegram; }, { type: "url", placeholder: LINKS.telegram, hint: "Where “Contact” and premium-license buttons in the app lead." })),
    h("div", { class: "card pad stack" }, h("h3", {}, "Announcement"),
      h("label", { class: "check" }, h("input", { type: "checkbox", checked: Boolean(ann), onchange: (e) => mutate((cat) => { cat.announcement = e.target.checked ? { id: new Date().toISOString().slice(0, 10), title: { en: "" } } : null; }) }), "Show an announcement card at the top of the app"),
      ann ? [
        field("Announcement ID", ann.id, (cat, v) => { cat.announcement.id = v; }, { hint: "Change it to show the card again to people who dismissed it." }),
        h("div", { class: "grid-2" },
          field("Title (English)", localize(ann.title, "en"), (cat, v) => { cat.announcement.title = { ...normalizeLocalized(cat.announcement.title), en: v }; }),
          field("Title (Hebrew)", normalizeLocalized(ann.title)?.he, (cat, v) => { const t = { ...normalizeLocalized(cat.announcement.title) }; if (v) t.he = v; else delete t.he; cat.announcement.title = t; }, { dir: "rtl" }),
          field("Text (English)", localize(ann.body, "en"), (cat, v) => { const b = { ...normalizeLocalized(cat.announcement.body) }; if (v) b.en = v; else delete b.en; cat.announcement.body = Object.keys(b).length ? b : undefined; }),
          field("Text (Hebrew)", normalizeLocalized(ann.body)?.he, (cat, v) => { const b = { ...normalizeLocalized(cat.announcement.body) }; if (v) b.he = v; else delete b.he; cat.announcement.body = Object.keys(b).length ? b : undefined; }, { dir: "rtl" })),
        field("Link (optional)", ann.url, (cat, v) => { if (v) cat.announcement.url = v; else delete cat.announcement.url; }, { type: "url" }),
      ] : null),
    h("div", { class: "card pad stack" }, h("h3", {}, "Where things are"),
      h("dl", { class: "kv" },
        h("dt", {}, "Catalog file"), h("dd", {}, h("a", { href: `${LINKS.repo}/blob/${BRANCH}/${CATALOG_PATH}`, target: "_blank" }, CATALOG_PATH)),
        h("dt", {}, "Published file"), h("dd", {}, h("a", { href: LINKS.publishedCatalog, target: "_blank" }, "catalog.json on the catalog release")),
        h("dt", {}, "Workflow"), h("dd", {}, h("a", { href: LINKS.actions, target: "_blank" }, "Actions > Catalog")),
        h("dt", {}, "Developer guide"), h("dd", {}, h("a", { href: LINKS.developerGuide, target: "_blank" }, "For third-party developers"))))));
}

// ------------------------------------------------------------------ history tab
function renderHistory(host) {
  const box = h("div", { class: "card", style: "margin-top:16px" }, h("div", { class: "empty" }, "Loading history…"));
  host.append(
    h("div", { class: "banner info", style: "margin-top:16px" }, icon("history"), h("div", { class: "grow" },
      "Every publish is a commit of ", h("code", {}, CATALOG_PATH), ". “Load this version” puts an earlier catalog into your draft; review it and publish to roll back. For an emergency, ",
      h("a", { href: LINKS.actions, target: "_blank" }, "Actions > Catalog > Run workflow > rollback"), " restores the previously published file at once.")),
    box);
  state.client.listCatalogCommits().then((commits) => {
    clear(box);
    if (!commits.length) return box.append(h("div", { class: "empty" }, "No history yet."));
    box.append(h("div", { class: "table-wrap" }, h("table", { class: "table" },
      h("thead", {}, h("tr", {}, ["Change", "By", "When", ""].map((t) => h("th", {}, t)))),
      h("tbody", {}, commits.map((commit, i) => h("tr", {},
        h("td", {}, h("a", { href: commit.html_url, target: "_blank" }, commit.commit.message.split("\n")[0]), " ", h("span", { class: "mono muted small" }, commit.sha.slice(0, 7))),
        h("td", {}, commit.author?.login ?? commit.commit.author?.name ?? ""),
        h("td", { class: "small muted" }, timeAgo(commit.commit.author?.date)),
        h("td", {}, i === 0 ? h("span", { class: "chip ok" }, "Current") : h("button", { class: "btn small", onclick: () => loadVersion(commit) }, icon("undo", 16), "Load this version"))))))));
  }).catch((e) => {
    clear(box);
    box.append(h("div", { class: "empty" }, `History could not be loaded: ${errorText(e)}`));
  });
}

async function loadVersion(commit) {
  try {
    const file = await state.client.getCatalogFile(commit.sha);
    if (!file.catalog) throw new Error("That version is not valid JSON.");
    const d = diffCatalogs(state.working, file.catalog);
    const yes = await confirmDialog({ title: "Load this version?", message: `Your draft becomes the catalog from ${commit.sha.slice(0, 7)} (${d.count} difference(s) from your current draft). Nothing is published until you choose “Review & publish”.`, confirm: "Load into draft" });
    if (!yes) return;
    file.catalog.schemaVersion = file.catalog.schemaVersion ?? state.working.schemaVersion;
    mutate((c) => { for (const k of Object.keys(c)) delete c[k]; Object.assign(c, clone(file.catalog)); });
    state.tab = "apps";
    render();
    toast("Loaded into your draft. Review and publish to restore it.");
  } catch (e) {
    handleError(e, "Loading that version");
  }
}

// ------------------------------------------------------------------ publish
function publishDialog() {
  const d = diff();
  const result = validateCatalog(state.working);
  const message = h("input", { type: "text", value: summarizeDiff(d), maxlength: 200, "aria-label": "Commit message" });
  const list = (title, ids, cls) => (ids.length ? h("div", {}, h("b", {}, title), h("div", { class: "chips", style: "margin-top:6px" }, ids.map((id) => h("span", { class: ["chip", cls] }, id)))) : null);
  const blocked = !result.ok;
  const publishBtn = h("button", { class: "btn primary", disabled: blocked || !d.count, onclick: () => { close(); runPublish(message.value.trim() || summarizeDiff(d)); } }, icon("send"), "Publish");
  const { close } = openDialog({
    title: "Review & publish",
    body: h("div", { class: "stack" },
      h("div", { class: "stack card pad" },
        list("New apps", d.apps.added, "ok"),
        list("Changed apps", d.apps.changed, "warn"),
        list("Removed apps", d.apps.removed, "bad"),
        d.apps.reordered ? h("div", {}, "The order of apps changed.") : null,
        d.categories.added.length + d.categories.removed.length + d.categories.changed.length || d.categories.reordered
          ? h("div", {}, h("b", {}, "Categories: "), [d.categories.added.length && `${d.categories.added.length} added`, d.categories.changed.length && `${d.categories.changed.length} changed`, d.categories.removed.length && `${d.categories.removed.length} removed`, d.categories.reordered && "reordered"].filter(Boolean).join(", "))
          : null,
        d.meta.length ? h("div", {}, h("b", {}, "Settings: "), d.meta.join(", ")) : null),
      blocked
        ? h("div", { class: "banner danger", role: "alert" }, icon("alert"), h("div", { class: "grow" }, h("b", {}, `${result.errors.length} problem(s) must be fixed before publishing:`),
          h("ul", {}, result.errors.slice(0, 30).map((e) => h("li", {}, `${describePath(e.path)}: ${e.message}`)))))
        : h("div", { class: "banner success" }, icon("check"), h("div", { class: "grow" }, "The catalog is valid.")),
      result.warnings.length ? h("details", { class: "more" }, h("summary", {}, `${result.warnings.length} warning(s)`), h("ul", { class: "problems" }, result.warnings.map((w) => h("li", { class: "warning" }, icon("alert", 16), `${describePath(w.path)}: ${w.message}`)))) : null,
      h("label", { class: "field" }, "Description of the change", message),
      h("p", { class: "small muted" }, `Publishing commits ${CATALOG_PATH} to ${BRANCH}. GitHub Actions then validates it again, resolves every app and replaces the published catalog. If anything fails, the current catalog stays published and you will see why.`)),
    footer: [h("button", { class: "btn", onclick: () => close() }, "Cancel"), publishBtn],
  });
}

function describePath(path) {
  const m = /^apps\[(\d+)\](.*)$/.exec(path);
  if (!m) return path;
  const app = state.working.apps[Number(m[1])];
  return `${app?.id ?? `app #${Number(m[1]) + 1}`}${m[2]}`;
}

async function runPublish(message) {
  const steps = {
    commit: { label: "Commit catalog.json", state: "busy", detail: "" },
    workflow: { label: "Validate and build (GitHub Actions)", state: "wait", detail: "" },
    release: { label: "Publish to the catalog release", state: "wait", detail: "" },
  };
  let finished = false;
  const stepsList = h("ol", { class: "steps" });
  const outcome = h("div");
  const draw = () => {
    clear(stepsList);
    for (const s of Object.values(steps)) {
      const dot = s.state === "done" ? icon("check", 16) : s.state === "fail" ? icon("x", 16) : s.state === "busy" ? icon("refresh", 16) : "";
      stepsList.append(h("li", {}, h("span", { class: ["step-dot", s.state] }, dot), h("div", {}, h("b", {}, s.label), s.detail ? h("div", { class: "small muted" }, s.detail) : null)));
    }
  };
  const { close } = openDialog({
    title: "Publishing",
    body: h("div", { class: "stack" }, stepsList, outcome),
    footer: [h("button", { class: "btn", onclick: () => close() }, "Close (keeps checking in the background)")],
    onClose: () => { if (finished) render(); },
  });
  draw();
  state.publishing = { startedAt: Date.now() };

  const text = serializeCatalog(state.working);
  let commit;
  try {
    commit = await state.client.putCatalogFile(text, state.baseSha, message);
  } catch (e) {
    state.publishing = null;
    steps.commit.state = "fail";
    steps.commit.detail = errorText(e);
    draw();
    if (e instanceof GitHubError && e.conflict) {
      close();
      await resolveConflict();
    } else if (e instanceof GitHubError && e.permission) {
      outcome.append(h("div", { class: "banner danger" }, icon("alert"), h("div", { class: "grow" },
        h("b", {}, "GitHub refused the change. "), "Edit the token on GitHub and set Repository permissions > Contents to “Read and write”, then try again. Your changes are still in your draft.")));
    } else {
      if (e instanceof GitHubError && e.auth) state.authProblem = errorText(e);
      outcome.append(h("div", { class: "banner danger" }, icon("alert"), h("div", { class: "grow" }, "Nothing was published. Your changes are still in your draft.")));
    }
    return;
  }
  state.base = clone(state.working);
  state.baseSha = commit.blobSha;
  draftStore.clear();
  steps.commit.state = "done";
  steps.commit.detail = h("a", { href: commit.commitUrl, target: "_blank" }, `Commit ${commit.commitSha.slice(0, 7)}`);
  steps.workflow.state = "busy";
  steps.workflow.detail = "Waiting for the Catalog workflow to start…";
  draw();
  render();

  const deadline = Date.now() + PUBLISH_TIMEOUT_MS;
  let lastRun = null;
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
    const runs = await state.client.workflowRuns(commit.commitSha);
    const run = runs?.[0] ?? null;
    if (run) {
      lastRun = run;
      steps.workflow.detail = h("span", {}, `${run.status === "completed" ? `Finished: ${run.conclusion}` : run.status === "in_progress" ? "Running" : "Queued"} · `, h("a", { href: run.html_url, target: "_blank" }, "open the run"));
      if (run.status === "completed") steps.workflow.state = run.conclusion === "success" ? "done" : "fail";
    } else if (runs === null) {
      steps.workflow.detail = "Run details are not readable from here; following the release status instead.";
    }
    let releaseInfo = null;
    try {
      releaseInfo = await state.client.getCatalogRelease();
    } catch (e) {
      if (e instanceof GitHubError && e.auth) state.authProblem = errorText(e);
    }
    const status = releaseInfo?.status;
    if (status && status.sourceBlob === commit.blobSha && status.at && Date.parse(status.at) >= state.publishing.startedAt - 60000) {
      state.releaseInfo = releaseInfo;
      if (status.state === "success" || status.state === "unchanged") {
        steps.workflow.state = "done";
        steps.release.state = "done";
        steps.release.detail = `Live${status.apps != null ? ` with ${status.apps} apps` : ""}. Phones see it on their next refresh.`;
        draw();
        clear(outcome);
        outcome.append(h("div", { class: "banner success" }, icon("check"), h("div", { class: "grow" }, h("b", {}, "Published. "),
          status.warnings?.length ? `${status.warnings.length} warning(s) from the build:` : "Everything resolved.",
          status.warnings?.length ? h("ul", {}, status.warnings.slice(0, 10).map((w) => h("li", {}, w))) : null)));
        finished = true;
        state.publishing = null;
        loadPublished();
        renderStatusStrip();
        return;
      }
      if (status.state === "failed") {
        steps.workflow.state = "fail";
        steps.release.state = "fail";
        steps.release.detail = "Not published: the previous catalog is still live.";
        draw();
        clear(outcome);
        outcome.append(h("div", { class: "banner danger", role: "alert" }, icon("alert"), h("div", { class: "grow" },
          h("b", {}, `The ${status.stage ?? "catalog"} step failed:`), h("ul", {}, (status.errors ?? []).map((e) => h("li", {}, e))),
          "Fix the problem in the dashboard and publish again.")));
        finished = true;
        state.publishing = null;
        renderStatusStrip();
        return;
      }
    }
    if (lastRun?.status === "completed" && lastRun.conclusion !== "success") {
      steps.release.state = "fail";
      steps.release.detail = "The workflow did not succeed; the previous catalog is still live.";
      draw();
      clear(outcome);
      outcome.append(h("div", { class: "banner danger", role: "alert" }, icon("alert"), h("div", { class: "grow" }, `The Catalog workflow ended with “${lastRun.conclusion}”. `, h("a", { href: lastRun.html_url, target: "_blank" }, "Open the run"), " to see why.")));
      finished = true;
      state.publishing = null;
      return;
    }
    if (lastRun?.status === "completed" && lastRun.conclusion === "success" && status?.sourceBlob !== commit.blobSha) {
      try {
        const head = await state.client.headCommit();
        if (head !== commit.commitSha) {
          steps.release.state = "done";
          steps.release.detail = "A newer change was committed after this one; its run publishes both.";
          draw();
          finished = true;
          state.publishing = null;
          return;
        }
      } catch {
        /* keep waiting */
      }
    }
    draw();
  }
  steps.release.state = "fail";
  steps.release.detail = "No result yet.";
  draw();
  clear(outcome);
  outcome.append(h("div", { class: "banner warning" }, icon("alert"), h("div", { class: "grow" },
    "GitHub has not reported a result within ", String(Math.round(PUBLISH_TIMEOUT_MS / 60000)), " minutes. Your change is committed; check ",
    h("a", { href: LINKS.actions, target: "_blank" }, "Actions > Catalog"), " for the run, or use Refresh on the status bar later.")));
  finished = true;
  state.publishing = null;
}

async function resolveConflict() {
  let theirs;
  try {
    theirs = await state.client.getCatalogFile();
  } catch (e) {
    return handleError(e, "Loading the newer catalog");
  }
  if (!theirs.catalog) return toast("The catalog on GitHub is not valid JSON; fix it there first.", "error");
  const { merged, conflicts } = mergeCatalogs(state.base, state.working, theirs.catalog);
  const mine = state.working;
  state.base = theirs.catalog;
  state.baseSha = theirs.sha;
  state.working = merged;
  saveDraft();
  render();
  if (conflicts.length) conflictDialog(conflicts, mine);
  else {
    toast("The catalog changed on GitHub meanwhile. Your changes were merged on top of it: review and publish again.");
    publishDialog();
  }
}

function conflictDialog(conflicts, mine) {
  const choices = new Map(conflicts.map((c) => [`${c.kind}:${c.id}`, "theirs"]));
  const describe = (v) => (v === null ? "deleted" : v?.name ? `“${typeof v.name === "string" ? v.name : localize(v.name, "en")}”` : "changed");
  const { close } = openDialog({
    title: "Resolve conflicting changes",
    body: h("div", { class: "stack" },
      h("p", {}, "These items were changed both in your draft and on GitHub. Choose which version to keep for each; everything else was merged automatically."),
      conflicts.map((c) => {
        const key = `${c.kind}:${c.id}`;
        const radio = (value, label) => h("label", { class: "check" }, h("input", { type: "radio", name: key, checked: choices.get(key) === value, onchange: () => choices.set(key, value) }), label);
        return h("div", { class: "card pad stack" }, h("b", {}, `${c.kind} ${c.id}`),
          h("div", { class: "row" }, radio("mine", `Keep mine (${describe(c.mine)})`), radio("theirs", `Keep GitHub's (${describe(c.theirs)})`)));
      })),
    footer: [h("button", { class: "btn primary", onclick: () => {
      mutate((cat) => {
        for (const c of conflicts) {
          if (choices.get(`${c.kind}:${c.id}`) !== "mine") continue;
          if (c.kind === "setting") { cat[c.id] = clone(c.mine); continue; }
          const list = c.kind === "app" ? cat.apps : cat.categories;
          const i = list.findIndex((x) => x.id === c.id);
          if (c.mine === null) { if (i >= 0) list.splice(i, 1); } else if (i >= 0) list[i] = clone(c.mine); else list.push(clone(c.mine));
        }
      });
      close();
      toast("Conflicts resolved. Review and publish when ready.");
    } }, "Apply")],
  });
  void mine;
}

// ------------------------------------------------------------------ start
async function start() {
  // GitHub Pages cannot send an X-Frame-Options header and a <meta> CSP cannot forbid framing,
  // so refuse to run inside a frame (clickjacking protection).
  if (window.top !== window.self) {
    clear(root);
    root.append(h("div", { class: "boot" }, "This dashboard cannot be shown inside another page. ", h("a", { href: location.href, target: "_blank" }, "Open it directly.")));
    return;
  }
  const token = tokenStore.get();
  if (!token) return renderSignIn();
  clear(root);
  root.append(h("div", { class: "boot" }, "Signing in…"));
  const client = new GitHubClient(token);
  try {
    const user = await client.getUser();
    let access = { canPush: false };
    try {
      access = await client.getRepoAccess();
    } catch (e) {
      if (e instanceof GitHubError && e.auth) throw e;
      state.authProblem = `This token cannot access ${FULL_REPO}: ${errorText(e)}`;
    }
    Object.assign(state, { client, user, access: { ...access, kind: tokenKind(token) } });
    await loadEverything();
  } catch (e) {
    if (e instanceof GitHubError && e.auth) {
      renderSignIn({ error: "Your saved token no longer works (expired or revoked). Paste a new one; the old one was kept until you replace it.", replacing: true });
    } else {
      renderSignIn({ error: `Could not sign in: ${errorText(e)}` });
    }
  }
}

start();

export { state as _stateForTests, isHttpsUrl as _isHttpsUrl, ID_PATTERN as _ID, RELEASE_STRATEGIES as _STRATEGIES };
