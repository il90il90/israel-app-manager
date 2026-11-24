// What the dashboard keeps in this browser's localStorage.
//
// The token is stored UNENCRYPTED, persistently, because the owner chose that (no extra
// password to type). Anyone who can run code in this origin or read this browser profile can
// read it: see the warning on the sign-in screen and docs/CATALOG_MANAGEMENT.md. It is never
// written anywhere else: not to the URL, not to logs, not to any other site.

const TOKEN_KEY = "iam.dashboard.token";
const DRAFT_KEY = "iam.dashboard.draft";

function read(key) {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key, value) {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}

export const tokenStore = {
  get: () => read(TOKEN_KEY),
  set: (token) => write(TOKEN_KEY, token),
  clear: () => write(TOKEN_KEY, null),
};

/** The unpublished draft: {baseSha, base, working, savedAt}. */
export const draftStore = {
  get() {
    const text = read(DRAFT_KEY);
    if (!text) return null;
    try {
      const draft = JSON.parse(text);
      return draft && typeof draft === "object" && draft.working && draft.base ? draft : null;
    } catch {
      return null;
    }
  },
  set(draft) {
    return write(DRAFT_KEY, JSON.stringify({ ...draft, savedAt: new Date().toISOString() }));
  },
  clear: () => write(DRAFT_KEY, null),
};

/** A token looks like a fine-grained token (github_pat_...) or a classic one (ghp_...). */
export function tokenKind(token) {
  if (/^github_pat_[A-Za-z0-9_]{20,}$/.test(token)) return "fine-grained";
  if (/^gh[pousr]_[A-Za-z0-9]{20,}$/.test(token)) return "classic";
  return "unknown";
}
