// Fixed settings of the dashboard. Nothing secret lives here (or anywhere in this site):
// the owner's token is typed in by the owner and kept only in their own browser.

export const OWNER = "il90il90";
export const REPO = "israel-app-manager";
export const FULL_REPO = `${OWNER}/${REPO}`;
export const BRANCH = "main";
export const CATALOG_PATH = "catalog/catalog.json";
export const RELEASE_TAG = "catalog";
export const WORKFLOW_FILE = "catalog.yml";
export const API = "https://api.github.com";

export const LINKS = Object.freeze({
  repo: `https://github.com/${FULL_REPO}`,
  release: `https://github.com/${FULL_REPO}/releases/tag/${RELEASE_TAG}`,
  publishedCatalog: `https://github.com/${FULL_REPO}/releases/download/${RELEASE_TAG}/catalog.json`,
  actions: `https://github.com/${FULL_REPO}/actions/workflows/${WORKFLOW_FILE}`,
  ownerGuide: `https://github.com/${FULL_REPO}/blob/${BRANCH}/docs/CATALOG_MANAGEMENT.md`,
  developerGuide: `https://github.com/${FULL_REPO}/blob/${BRANCH}/docs/DEVELOPER_GUIDE.md`,
  newToken: "https://github.com/settings/personal-access-tokens/new",
  tokens: "https://github.com/settings/personal-access-tokens",
  telegram: "https://t.me/YisraelCohen",
});

// How long to follow a publish before saying "no answer yet" (the workflow usually needs 1 to 4 minutes).
export const PUBLISH_TIMEOUT_MS = 12 * 60 * 1000;
export const POLL_INTERVAL_MS = 6000;
