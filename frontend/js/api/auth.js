/**
 * 用户认证、权限状态与会话生命周期管理
 */

export const TOKEN_KEY = "sh.token";
export const USER_KEY = "sh.username";
export const ROLE_KEY = "sh.role";
export const SESSION_KEY = "sh.sessionId";
export const WORK_KEY = "sh.workId";

export const ROLE_LABELS = {
  owner: "站长",
  admin: "管理员",
  user: "用户",
};

export const WS_BASE = typeof location !== "undefined"
  ? `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}`
  : "";

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {}
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {}
}

export function getUsername() {
  try {
    return localStorage.getItem(USER_KEY) || "";
  } catch {
    return "";
  }
}

export function setUsername(name) {
  try {
    if (name) localStorage.setItem(USER_KEY, name);
    else localStorage.removeItem(USER_KEY);
  } catch {}
}

export function getRole() {
  try {
    return localStorage.getItem(ROLE_KEY) || "user";
  } catch {
    return "user";
  }
}

export function setRole(role) {
  try {
    if (role) localStorage.setItem(ROLE_KEY, role);
    else localStorage.removeItem(ROLE_KEY);
  } catch {}
}

export function isStaff() {
  const role = getRole();
  return role === "owner" || role === "admin";
}

/**
 * 跳转到独立登录页
 * @param {string} [reason] - expired: 登录过期, disabled: 账号被禁用
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

export function logout() {
  clearToken();
  try {
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(SESSION_KEY);
  } catch {}
  goLogin();
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
