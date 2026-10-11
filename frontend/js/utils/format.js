/**
 * 格式化与文本解析工具
 */

export function fmtNum(n) {
  return n == null ? "0" : Number(n).toLocaleString();
}

/**
 * 积分展示：最多 6 位小数并去掉尾零（DECIMAL(20,6) 序列化带尾零）
 */
export function fmtPoints(n) {
  const v = Number(n ?? 0);
  return parseFloat(v.toFixed(6)).toLocaleString(undefined, { maximumFractionDigits: 6 });
}

export function fmtBytes(bytes) {
  const n = Number(bytes || 0);
  if (n >= 1 << 30) return (n / (1 << 30)).toFixed(1) + " GB";
  if (n >= 1 << 20) return (n / (1 << 20)).toFixed(1) + " MB";
  if (n >= 1 << 10) return (n / (1 << 10)).toFixed(1) + " KB";
  return n + " B";
}

export function formatSize(bytes) {
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + "MB";
  return Math.max(1, Math.round(bytes / 1024)) + "KB";
}

/**
 * 相对友好时间展示：刚刚 / 5m / 2h / 3d / 日期
 */
export function formatTime(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d)) return String(iso);
  const min = Math.floor((Date.now() - d.getTime()) / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return `${min}m`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h}h`;
  const day = Math.floor(h / 24);
  if (day < 30) return `${day}d`;
  return d.toLocaleDateString("zh-CN", { month: "2-digit", day: "2-digit" });
}

/**
 * 紧凑时间：YYYY-MM-DD HH:mm（补齐零、无秒），管理表格用
 */
export function fmtDT(iso) {
  if (!iso) return "-";
  const d = new Date(iso);
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function fmtTime(iso) {
  return iso ? new Date(iso).toLocaleString() : "-";
}

export function fmtTimeStandard(iso) {
  return fmtTime(iso);
}

/**
 * 解析非负数字输入；空串或非法返回 null
 */
export function parseNonNegative(text) {
  const s = String(text ?? "").trim();
  if (s === "") return null;
  const n = Number(s);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

export function relPath(path, workspace = "") {
  const norm = (s) => (s || "").replace(/\\/g, "/");
  const p = norm(path);
  const ws = norm(workspace).replace(/\/+$/, "");
  if (ws && p.toLowerCase().startsWith(ws.toLowerCase() + "/")) {
    const rest = p.slice(ws.length + 1).replace(/^\/+/, "");
    if (rest) return rest;
  }
  return p;
}

export function splitPath(path) {
  const slash = path.lastIndexOf("/");
  return slash >= 0 ? { dir: path.slice(0, slash), name: path.slice(slash + 1) } : { dir: "", name: path };
}

export function stringifyContent(content) {
  if (typeof content === "string") return content;
  try {
    return JSON.stringify(content, null, 2);
  } catch {
    return String(content);
  }
}
