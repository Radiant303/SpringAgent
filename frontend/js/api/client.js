/**
 * 统一 HTTP 请求客户端
 */

import { getToken, getValidToken, goLogin, refreshAccessToken } from "./auth.js";

function authReason(response) {
  return response.headers.get("X-Auth-Error") || "token_invalid";
}

function rejectAuthentication(reason) {
  const expired = reason === "token_expired";
  goLogin(expired ? "expired" : "invalid");
  throw new Error(expired ? "登录已过期，请重新登录" : "登录状态无效，请重新登录");
}

function handleRefreshError(error) {
  if (error?.authReason) rejectAuthentication(error.authReason);
  throw error;
}

async function send(path, options, token) {
  const headers = { ...(options.headers || {}) };
  const isForm = typeof FormData !== "undefined" && options.body instanceof FormData;
  if (options.body && !isForm && !Object.keys(headers).some((key) => key.toLowerCase() === "content-type")) {
    headers["Content-Type"] = "application/json";
  }
  if (token) headers.Authorization = "Bearer " + token;
  try {
    return await fetch(path, { ...options, headers });
  } catch (err) {
    throw new Error(err?.message || "网络请求失败，请检查网络连接");
  }
}

/**
 * 底层 Fetch 包装：自动附加 Bearer Token、续期并重放过期请求
 * @param {string} path
 * @param {RequestInit} [options]
 * @returns {Promise<Response>}
 */
export async function apiFetch(path, options = {}) {
  let token;
  try {
    token = await getValidToken();
  } catch (error) {
    handleRefreshError(error);
  }
  let response = await send(path, options, token);
  if (response.status !== 401) return response;
  const currentToken = getToken();
  if (token && currentToken && currentToken !== token) {
    token = currentToken;
    response = await send(path, options, token);
    if (response.status !== 401) return response;
  }
  if (authReason(response) === "token_expired") {
    try {
      token = await refreshAccessToken();
    } catch (error) {
      handleRefreshError(error);
    }
    response = await send(path, options, token);
    if (response.status !== 401) return response;
  }
  rejectAuthentication(authReason(response));
}

/**
 * 业务 API 调用：自动解析 JSON payload，剥离 data 字段，校验错误状态
 * @param {string} path
 * @param {object} [options]
 * @returns {Promise<any>}
 */
export async function api(path, options = {}) {
  const fetchOptions = {
    method: options.method || "GET",
    headers: options.headers,
    body: options.body ? (typeof options.body === "string" ? options.body : JSON.stringify(options.body)) : undefined,
  };
  const r = await apiFetch(path, fetchOptions);
  const payload = await r.json().catch(() => null);
  if (!r.ok) {
    throw new Error((payload && payload.message) || `请求失败（${r.status}）`);
  }
  return payload ? payload.data : null;
}

/**
 * 业务文本 API 调用：校验错误状态并返回完整文本
 * @param {string} path
 * @param {object} [options]
 * @returns {Promise<string>}
 */
export async function apiText(path, options = {}) {
  const fetchOptions = {
    method: options.method || "GET",
    headers: options.headers,
    body: options.body ? (typeof options.body === "string" ? options.body : JSON.stringify(options.body)) : undefined,
  };
  const r = await apiFetch(path, fetchOptions);
  if (!r.ok) {
    const payload = await r.json().catch(() => null);
    throw new Error((payload && payload.message) || `请求失败（${r.status}）`);
  }
  return r.text();
}
