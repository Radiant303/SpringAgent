/**
 * 站长端新版本提示。
 */

import { $ } from "../../utils/dom.js";
import { api } from "../../api/client.js";
import { getRole } from "../../api/auth.js";
import { compareVersions, parseVersion } from "../../utils/version.js";

const RELEASE_API = "https://api.github.com/repos/Radiant303/SpringAgent/releases/latest";
const CACHE_KEY = "sa.latestRelease";
const CACHE_TTL = 6 * 60 * 60 * 1000;

function readCache() {
  try {
    const cached = JSON.parse(sessionStorage.getItem(CACHE_KEY) || "null");
    if (cached && Date.now() - cached.checkedAt < CACHE_TTL) return cached;
  } catch (e) { /* 缓存损坏时重新请求 */ }
  return null;
}

async function fetchLatestRelease() {
  const cached = readCache();
  if (cached) return cached;

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  let response;
  try {
    response = await fetch(RELEASE_API, {
      headers: { Accept: "application/vnd.github+json" },
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
  if (!response.ok) return null;

  const data = await response.json();
  const release = {
    tag: String(data.tag_name || ""),
    url: String(data.html_url || ""),
    checkedAt: Date.now(),
  };
  try {
    sessionStorage.setItem(CACHE_KEY, JSON.stringify(release));
  } catch (e) { /* 存储不可用时只影响缓存 */ }
  return release;
}

export async function initVersionNotice() {
  if (getRole() !== "owner") return;
  const notice = $("update-notice");
  if (!notice) return;

  let current;
  try {
    const config = await api("/api/auth/public-config");
    current = config?.version;
  } catch (e) {
    return;
  }
  if (!parseVersion(current)) return;

  let release;
  try {
    release = await fetchLatestRelease();
  } catch (e) {
    return;
  }
  if (!release || !parseVersion(release.tag)) return;
  if (compareVersions(current, release.tag) >= 0) return;

  const currentLabel = "v" + String(current).replace(/^v/, "");
  const latestLabel = "v" + String(release.tag).replace(/^v/, "");
  if (/^https:\/\/github\.com\//.test(release.url)) notice.href = release.url;
  const sub = $("update-version");
  if (sub) sub.textContent = currentLabel + " → " + latestLabel;
  notice.title = "版本更新 " + latestLabel;
  notice.classList.remove("hidden");
}
