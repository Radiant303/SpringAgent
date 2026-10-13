import { api } from "../api/client.js";
import { parseDecimal, parseInteger } from "./numeric.js";

let request;

export async function loadResourceLimits() {
  if (!request) {
    request = api("/api/admin/resource-limits").catch((error) => {
      request = null;
      throw error;
    });
  }
  return request;
}

export function pointsValue(text, limits, options = {}) {
  return parseDecimal(text, { max: limits.pointsMax, scale: limits.pointsScale, ...options });
}

export function quotaValue(text, limits, options = {}) {
  return parseInteger(text, { max: limits.quotaMbMax, ...options });
}

export function pointsHint(limits, signed = false) {
  return `${signed ? "绝对值不超过" : "0～"}${limits.pointsMax}，最多 ${limits.pointsScale} 位小数${signed ? "，不能为 0" : ""}`;
}

export function quotaHint(limits) {
  return `0～${limits.quotaMbMax} MB（1 TB），仅限整数`;
}
