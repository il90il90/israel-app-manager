// GitHub REST API client for the dashboard.
//
// The token is only ever sent to https://api.github.com, and only for this repository and for
// /user (to say hello). Lookups of other repositories (link previews) go out without it.
// Nothing here logs, stores or forwards the token.

import { API, BRANCH, CATALOG_PATH, FULL_REPO, RELEASE_TAG, WORKFLOW_FILE } from "./config.js";

export class GitHubError extends Error {
  constructor(status, message, { hint = "", conflict = false, auth = false, permission = false, rateLimited = false } = {}) {
    super(message);
    this.status = status;
    this.hint = hint;
    this.conflict = conflict;
    this.auth = auth;
    this.permission = permission;
    this.rateLimited = rateLimited;
  }
}

/** UTF-8 safe base64 (the catalog has Hebrew text). */
export function encodeBase64(text) {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(binary);
}

export function decodeBase64(b64) {
  const binary = atob(String(b64).replace(/\s/g, ""));
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

/** Reads the status the Catalog workflow writes into the release description. */
export function readReleaseStatus(body) {
  const match = /<!-- catalog-status (\{[\s\S]*?\}) -->/.exec(body ?? "");
  if (!match) return null;
  try {
    return JSON.parse(match[1]);
  } catch {
    return null;
  }
}

async function errorFrom(response) {
  let message = `GitHub answered ${response.status}`;
  try {
    const body = await response.json();
    if (body?.message) message = body.message;
  } catch {
    /* no JSON body */
  }
  const status = response.status;
  const remaining = response.headers.get("x-ratelimit-remaining");
  if (status === 401) {
    return new GitHubError(status, "The token was rejected.", {
      auth: true,
      hint: "It may have expired or been revoked. Create a new token and use “Replace token”.",
    });
  }
  if ((status === 403 || status === 429) && remaining === "0") {
    return new GitHubError(status, "GitHub's rate limit was reached.", { rateLimited: true, hint: "Wait a few minutes and try again." });
  }
  if (status === 403) {
    return new GitHubError(status, message, {
      permission: true,
      hint: `The token is not allowed to do this. It needs access to ${FULL_REPO} with “Contents: Read and write”.`,
    });
  }
  if (status === 404) {
    return new GitHubError(status, message, {
      permission: true,
      hint: `Not found. Check that the token's “Repository access” includes ${FULL_REPO}.`,
    });
  }
  if (status === 409 || (status === 422 && /sha/i.test(message))) {
    return new GitHubError(status, message, {
      conflict: true,
      hint: "The catalog changed on GitHub since it was loaded here.",
    });
  }
  return new GitHubError(status, message);
}

export class GitHubClient {
  constructor(token, fetchImpl = (...args) => fetch(...args)) {
    this.token = token;
    this.fetch = fetchImpl;
  }

  async request(path, { method = "GET", body, raw = false } = {}) {
    if (!path.startsWith("/user") && !path.startsWith(`/repos/${FULL_REPO}/`) && path !== `/repos/${FULL_REPO}`) {
      throw new Error("The token is only used for this repository.");
    }
    let response;
    try {
      response = await this.fetch(`${API}${path}`, {
        method,
        headers: {
          Accept: "application/vnd.github+json",
          Authorization: `Bearer ${this.token}`,
          "X-GitHub-Api-Version": "2022-11-28",
          ...(body ? { "Content-Type": "application/json" } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        cache: "no-store",
        credentials: "omit",
        referrerPolicy: "no-referrer",
      });
    } catch {
      throw new GitHubError(0, "GitHub could not be reached.", { hint: "Check the internet connection and try again." });
    }
    if (!response.ok) throw await errorFrom(response);
    if (raw) return response;
    return response.status === 204 ? null : response.json();
  }

  getUser() {
    return this.request("/user");
  }

  async getRepoAccess() {
    const response = await this.request(`/repos/${FULL_REPO}`, { raw: true });
    const repo = await response.json();
    return {
      repo,
      // Present for tokens that expire; GitHub may not expose it to browsers.
      expiresAt: response.headers.get("github-authentication-token-expiration"),
      // What GitHub reports about the account (fine-grained tokens can still be narrower).
      canPush: repo?.permissions?.push === true,
    };
  }

  /** catalog/catalog.json on main: {text, sha (blob), catalog (parsed or null), parseError}. */
  async getCatalogFile(ref = BRANCH) {
    const file = await this.request(`/repos/${FULL_REPO}/contents/${CATALOG_PATH}?ref=${encodeURIComponent(ref)}`);
    const text = decodeBase64(file.content);
    let catalog = null;
    let parseError = null;
    try {
      catalog = JSON.parse(text);
    } catch (e) {
      parseError = e.message;
    }
    return { text, sha: file.sha, catalog, parseError };
  }

  /** Commits catalog/catalog.json. `sha` is the blob it replaces: GitHub refuses if it changed meanwhile. */
  async putCatalogFile(text, sha, message) {
    const result = await this.request(`/repos/${FULL_REPO}/contents/${CATALOG_PATH}`, {
      method: "PUT",
      body: { message, content: encodeBase64(text), sha, branch: BRANCH },
    });
    return { blobSha: result.content.sha, commitSha: result.commit.sha, commitUrl: result.commit.html_url };
  }

  /** The catalog release and its status, or null when it does not exist yet. */
  async getCatalogRelease() {
    try {
      const release = await this.request(`/repos/${FULL_REPO}/releases/tags/${RELEASE_TAG}`);
      return { release, status: readReleaseStatus(release.body) };
    } catch (e) {
      if (e instanceof GitHubError && e.status === 404) return null;
      throw e;
    }
  }

  listCatalogCommits(perPage = 20) {
    return this.request(`/repos/${FULL_REPO}/commits?path=${encodeURIComponent(CATALOG_PATH)}&sha=${BRANCH}&per_page=${perPage}`);
  }

  /** The newest commit on main (to tell whether a newer change superseded ours). */
  async headCommit() {
    const branch = await this.request(`/repos/${FULL_REPO}/branches/${BRANCH}`);
    return branch.commit.sha;
  }

  /**
   * Catalog workflow runs for a commit. Fine-grained tokens with only Contents access cannot
   * read Actions, so this asks without the token (the repository is public). Returns null if
   * GitHub does not answer; the release status remains the source of truth.
   */
  async workflowRuns(headSha) {
    try {
      const response = await this.fetch(
        `${API}/repos/${FULL_REPO}/actions/workflows/${WORKFLOW_FILE}/runs?head_sha=${encodeURIComponent(headSha)}&per_page=5`,
        { headers: { Accept: "application/vnd.github+json" }, cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer" },
      );
      if (!response.ok) return null;
      const body = await response.json();
      return body.workflow_runs ?? [];
    } catch {
      return null;
    }
  }
}

/** A public lookup without the token (other repositories, previews). */
export async function anonymousJson(url) {
  const response = await fetch(url, { cache: "no-store", credentials: "omit", referrerPolicy: "no-referrer", headers: { Accept: "application/json" } });
  if (response.status === 404) return null;
  if (!response.ok) {
    const limited = response.headers.get("x-ratelimit-remaining") === "0";
    throw new Error(limited ? "GitHub's anonymous rate limit was reached; try again later." : `HTTP ${response.status}`);
  }
  return response.json();
}
