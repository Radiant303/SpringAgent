/**
 * 用户认证、权限状态与会话生命周期管理
 */

export const TOKEN_KEY = "sa.token";
export const REFRESH_TOKEN_KEY = "sa.refreshToken";
export const TOKEN_EXPIRY_KEY = "sa.tokenExpiresAt";
export const USER_KEY = "sa.username";
export const ROLE_KEY = "sa.role";
export const SESSION_KEY = "sa.sessionId";
export const WORK_KEY = "sa.workId";

export const ROLE_LABELS = {
  owner: "站长",
  admin: "管理员",
  user: "用户",
};

export const WS_BASE = typeof location !== "undefined"
  ? `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}`
  : "";

let refreshPromise = null;

function storageGet(key) {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key, value) {
  try {
    if (value) localStorage.setItem(key, value);
    else localStorage.removeItem(key);
  } catch {}
}

export function getToken() {
  return storageGet(TOKEN_KEY);
}

export function getRefreshToken() {
  return storageGet(REFRESH_TOKEN_KEY);
}

export function setToken(token) {
  storageSet(TOKEN_KEY, token);
}

export function saveSession(data) {
  storageSet(TOKEN_KEY, data.token);
  storageSet(REFRESH_TOKEN_KEY, data.refresh_token);
  const expiresIn = Number(data.expires_in || 3600);
  storageSet(TOKEN_EXPIRY_KEY, String(Date.now() + expiresIn * 1000));
  storageSet(USER_KEY, data.username);
  storageSet(ROLE_KEY, data.role || "user");
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(TOKEN_EXPIRY_KEY);
  } catch {}
}

export function getUsername() {
  return storageGet(USER_KEY) || "";
}

export function setUsername(name) {
  storageSet(USER_KEY, name);
}

export function getRole() {
  return storageGet(ROLE_KEY) || "user";
}

export function setRole(role) {
  storageSet(ROLE_KEY, role);
}

export function isStaff() {
  const role = getRole();
  return role === "owner" || role === "admin";
}

async function requestRefresh() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    const error = new Error("登录状态无效，请重新登录");
    error.authReason = "token_invalid";
    throw error;
  }
  let response;
  try {
    response = await fetch("/api/auth/refresh", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
    });
  } catch (err) {
    throw new Error(err?.message || "网络请求失败，请检查网络连接");
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(payload?.message || `请求失败（${response.status}）`);
    if (response.status === 401) {
      error.authReason = response.headers.get("X-Auth-Error") || "token_invalid";
    }
    throw error;
  }
  if (!payload?.data?.token || !payload?.data?.refresh_token) {
    throw new Error("刷新登录状态失败，请稍后重试");
  }
  saveSession(payload.data);
  return payload.data.token;
}

/**
 * 刷新当前会话；同源标签页通过 Web Locks 串行续期
 * @returns {Promise<string>} 新访问令牌
 */
export function refreshAccessToken() {
  if (refreshPromise) return refreshPromise;
  const run = () => requestRefresh();
  const pending = typeof navigator !== "undefined" && navigator.locks?.request
    ? navigator.locks.request("sa.auth.refresh", run)
    : run();
  refreshPromise = pending.finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

/**
 * 返回可用访问令牌，临期时先续期
 * @returns {Promise<string|null>}
 */
export async function getValidToken() {
  const token = getToken();
  if (!token) return null;
  const expiresAt = Number(storageGet(TOKEN_EXPIRY_KEY) || 0);
  if (expiresAt > 0 && Date.now() >= expiresAt - 30000) {
    return refreshAccessToken();
  }
  return token;
}

/**
 * 跳转到独立登录页
 * @param {string} [reason] - expired: 登录过期, invalid: 会话无效, disabled: 账号被禁用
 * @param {string} [nextUrl] - 登录成功后回跳地址
 */
export function goLogin(reason, nextUrl) {
  clearToken();
  const q = new URLSearchParams();
  if (reason) q.set("reason", reason);
  const next = nextUrl || (typeof location !== "undefined" ? location.pathname + location.hash : "");
  if (next && next !== "/static/login.html") q.set("next", next);
  const query = q.toString() ? "?" + q.toString() : "";
  if (typeof location !== "undefined") {
    location.replace("/static/login.html" + query);
  }
}

/**
 * 删除服务端当前会话并清理本端登录数据
 */
export async function logout() {
  if (refreshPromise) await refreshPromise.catch(() => {});
  const refreshToken = getRefreshToken();
  try {
    if (refreshToken) {
      await fetch("/api/auth/logout", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: refreshToken }),
        keepalive: true,
      });
    }
  } catch {} finally {
    clearToken();
    try {
      localStorage.removeItem(USER_KEY);
      localStorage.removeItem(ROLE_KEY);
      localStorage.removeItem(SESSION_KEY);
    } catch {}
    goLogin();
  }
}

/**
 * 页面头部安全防护钩子：未登录直接前往登录页，防止内容闪现
 */
export function guardAuth(includeNext = true) {
  try {
    if (!localStorage.getItem(TOKEN_KEY)) {
      const next = includeNext ? location.pathname + location.hash : null;
      goLogin(null, next);
      return false;
    }
    return true;
  } catch {
    return true;
  }
}
