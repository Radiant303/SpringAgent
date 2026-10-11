/**
 * 统一 HTTP 请求客户端
 */

import { getToken, goLogin } from "./auth.js";

/**
 * 底层 Fetch 包装：自动附加 Bearer Token，处理 401 过期并抛错
 * @param {string} path
 * @param {RequestInit} [options]
 * @returns {Promise<Response>}
 */
export async function apiFetch(path, options = {}) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  const token = getToken();
  if (token) {
    headers["Authorization"] = "Bearer " + token;
  }
  let r;
  try {
    r = await fetch(path, { ...options, headers });
  } catch (err) {
    throw new Error(err?.message || "网络请求失败，请检查网络连接");
  }
  if (r.status === 401) {
    goLogin("expired");
    throw new Error("登录已过期，请重新登录");
  }
  return r;
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
