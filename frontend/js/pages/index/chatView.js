/**
 * 消息节点、工具卡片、Diff 与文件视图
 */

import { $, el, icon } from "../../utils/dom.js";
import { setMarkdown } from "../../utils/markdown.js";
import { relPath, splitPath } from "../../utils/format.js";
import { hlLang, highlightLine } from "../../utils/highlight.js";
import { state, closeWork, maybeScroll } from "./state.js";

/* ================= 基础消息节点 ================= */

const ATTACH_IMAGE_RE = /!\[([^\]]*)\]\((https?:\/\/[^\s)]+)\)/g;

function isImageAttachment(att) {
  return Boolean(
    (att?.mimeType && att.mimeType.startsWith("image/")) ||
    /\.(png|jpe?g|gif|webp|svg|bmp|ico)$/i.test(att?.name || "")
  );
}

export function attachChipNode(att) {
  const chip = el("span", "attach-chip");
  chip.title = att.name;
  const isImg = isImageAttachment(att);
  const iconSpan = el("span", "attach-icon");
  iconSpan.appendChild(icon(isImg ? "image" : "file"));
  chip.append(iconSpan, el("span", "attach-name", att.name));
  if (isImg && att.url) {
    chip.addEventListener("mouseenter", () => showFloatPreview(chip, att));
    chip.addEventListener("mouseleave", () => hideFloatPreview());
  }
  return chip;
}

export function userNode(text) {
  const wrap = el("div", "msg user");
  const body = el("div", "msg-text");
  let offset = 0;
  for (const match of (text || "").matchAll(ATTACH_IMAGE_RE)) {
    body.appendChild(document.createTextNode(text.slice(offset, match.index)));
    body.appendChild(attachChipNode({ name: match[1], url: match[2] }));
    offset = match.index + match[0].length;
  }
  body.appendChild(document.createTextNode((text || "").slice(offset)));
  wrap.appendChild(body);
  return wrap;
}

/* 附件 chip 浮动预览 */
let floatPreview = null;
let floatPreviewTimer = null;
let floatPreviewListenersAttached = false;

function onFloatPreviewScroll() {
  hideFloatPreview(true);
}

export function hideFloatPreview(immediate = false) {
  clearTimeout(floatPreviewTimer);
  if (immediate) {
    if (floatPreviewListenersAttached) {
      document.removeEventListener("scroll", onFloatPreviewScroll, true);
      window.removeEventListener("resize", onFloatPreviewScroll);
      floatPreviewListenersAttached = false;
    }
    floatPreview?.remove();
    floatPreview = null;
    return;
  }
  floatPreviewTimer = setTimeout(() => {
    hideFloatPreview(true);
  }, 100);
}

function showFloatPreview(chip, att) {
  clearTimeout(floatPreviewTimer);
  hideFloatPreview(true);
  const box = el("div", "attach-preview");
  const img = document.createElement("img");
  img.src = att.url;
  img.alt = att.name;
  box.appendChild(img);
  document.body.appendChild(box);

  const chipRect = chip.getBoundingClientRect();
  const margin = 12;
  const gap = 8;
  const spaceAbove = chipRect.top - margin;
  const spaceBelow = window.innerHeight - chipRect.bottom - margin;
  const placeAbove = spaceAbove >= 240 || spaceAbove >= spaceBelow;
  const maxH = Math.min(220, Math.max(100, Math.floor((placeAbove ? spaceAbove : spaceBelow) - 24)));
  img.style.maxHeight = maxH + "px";

  if (placeAbove) {
    box.style.bottom = (window.innerHeight - chipRect.top + gap) + "px";
    box.style.top = "auto";
  } else {
    box.style.top = (chipRect.bottom + gap) + "px";
    box.style.bottom = "auto";
  }

  if (chipRect.left + chipRect.width / 2 > window.innerWidth / 2) {
    box.style.right = Math.max(margin, window.innerWidth - chipRect.right) + "px";
    box.style.left = "auto";
  } else {
    box.style.left = Math.max(margin, chipRect.left) + "px";
    box.style.right = "auto";
  }

  box.addEventListener("mouseenter", () => clearTimeout(floatPreviewTimer));
  box.addEventListener("mouseleave", () => hideFloatPreview());
  if (!floatPreviewListenersAttached) {
    document.addEventListener("scroll", onFloatPreviewScroll, true);
    window.addEventListener("resize", onFloatPreviewScroll);
    floatPreviewListenersAttached = true;
  }
  floatPreview = box;
}

/**
 * 创建可移除的内联附件。
 *
 * @param {{id: string, name: string, url: string}} att 附件
 * @param {{onRemove?: Function}} [opts] 操作回调
 * @returns {HTMLSpanElement} 附件节点
 */
export function editorChipNode(att, opts = {}) {
  const { onRemove = null } = opts;
  const chip = el("span", "attach-chip editor-chip");
  chip.contentEditable = "false";
  chip.dataset.attachId = att.id;
  chip.dataset.attachName = att.name;
  chip.dataset.attachUrl = att.url;
  chip.title = att.name;
  const remove = el("button", "attach-remove");
  remove.type = "button";
  remove.title = "移除附件";
  remove.appendChild(icon("close"));
  remove.onclick = (e) => {
    e.stopPropagation();
    onRemove?.();
  };
  chip.appendChild(remove);
  const isImg = isImageAttachment(att);
  const iconSpan = el("span", "attach-icon");
  iconSpan.appendChild(icon(isImg ? "image" : "file"));
  chip.append(iconSpan, el("span", "attach-name", att.name));
  if (isImg && att.url) {
    chip.addEventListener("mouseenter", () => showFloatPreview(chip, att));
    chip.addEventListener("mouseleave", () => hideFloatPreview());
  }
  return chip;
}

export function systemNode(text, isError = false) {
  return el("div", "msg system" + (isError ? " error" : ""), text);
}

export function answerNode(text) {
  const wrap = el("div", "msg assistant");
  const answer = el("div", "answer markdown-body");
  if (text) setMarkdown(answer, text);
  wrap.appendChild(answer);
  return wrap;
}

export function addSystem(text, isError = false) {
  closeWork();
  state.assistant = null;
  const msgBox = $("messages");
  if (msgBox) msgBox.appendChild(systemNode(text, isError));
  maybeScroll();
}

/* ================= 工具元数据 ================= */

export const TOOL_META = {
  edit_file: { icon: "edit", verb: "编辑", kind: "edit", targetKeys: ["path"] },
  write_file: { icon: "edit", verb: "写入", kind: "edit", targetKeys: ["path"] },
  create_directory: { icon: "toolCreateDirectory", verb: "创建目录", kind: "other", targetKeys: ["path"] },
  delete_path: { icon: "toolTrash", verb: "删除", kind: "other", targetKeys: ["path"] },
  read_file: { icon: "file", verb: "读取", kind: "read", targetKeys: ["path"] },
  file_info: { icon: "file", verb: "查看", kind: "read", targetKeys: ["path"] },
  list_directory: { icon: "folder", verb: "列出", kind: "list", targetKeys: ["path"] },
  search_files: { icon: "search", verb: "搜索", kind: "search", targetKeys: ["pattern", "path"] },
  find_files: { icon: "search", verb: "查找", kind: "search", targetKeys: ["pattern", "path"] },
  web_search: { icon: "toolWebSearch", verb: "搜索网页", kind: "search", targetKeys: ["query"] },
  page_url: { icon: "link", verb: "获取页面地址", kind: "other", targetKeys: [] },
  current_session: { icon: "toolCurrentSession", verb: "查看会话", kind: "other", targetKeys: [] },
  view_url_image: { icon: "toolImage", verb: "查看网络图片", kind: "other", targetKeys: ["url"] },
  view_local_image: { icon: "toolImage", verb: "查看本地图片", kind: "other", targetKeys: ["path"] },
  run_command: { icon: "terminal", verb: "运行", kind: "command", targetKeys: ["command"] },
  run_code: { icon: "terminal", verb: "代码模式", kind: "other", targetKeys: [] },
  start_command: { icon: "toolStartCommand", verb: "后台运行", kind: "command", targetKeys: ["command"] },
  check_command: { icon: "toolCheckCommand", verb: "查看命令", kind: "other", targetKeys: ["command_id"] },
  stop_command: { icon: "toolStopCommand", verb: "停止命令", kind: "other", targetKeys: ["command_id"] },
  ask_user: { icon: "question", verb: "提问", kind: "other", targetKeys: ["question"] },
  teach_create_unit: { icon: "toolCreateUnit", verb: "创建教学单元", kind: "other", targetKeys: ["title", "slug"] },
  teach_state: { icon: "toolTeachState", verb: "查看教学状态", kind: "other", targetKeys: [] },
  teach_record_hint: { icon: "bulb", verb: "记录提示", kind: "other", targetKeys: ["objective_id"] },
  teach_update_mastery: { icon: "toolUpdateMastery", verb: "更新掌握程度", kind: "other", targetKeys: ["objective_id"] },
  teach_bump_version: { icon: "toolBumpVersion", verb: "更新教学版本", kind: "other", targetKeys: ["reason"] },
  teach_close_unit: { icon: "check", verb: "结束教学单元", kind: "other", targetKeys: ["summary"] },
  write_plan: { icon: "edit", verb: "写入计划", kind: "other", targetKeys: ["items"] },
  read_plan: { icon: "toolReadPlan", verb: "查看计划", kind: "other", targetKeys: [] },
  add_task: { icon: "plus", verb: "添加任务", kind: "other", targetKeys: ["active_form", "content"] },
  update_task_status: { icon: "toolReadPlan", verb: "更新任务状态", kind: "other", targetKeys: ["task_id"] },
  update_task_statuses: { icon: "toolReadPlan", verb: "更新任务状态", kind: "other", targetKeys: ["updates"] },
  remove_task: { icon: "toolRemoveTask", verb: "移除任务", kind: "other", targetKeys: ["task_id"] },
  delegate_task: { icon: "toolDelegateTask", verb: "委派任务", kind: "other", targetKeys: ["agent_name"] },
  load_capability: { icon: "toolPlug", verb: "加载能力", kind: "other", targetKeys: ["id"] },
};

export const TARGET_KEYS = ["path", "command", "pattern", "query", "question", "command_id"];

export function toolMeta(name) {
  return TOOL_META[name] || { icon: "tool", verb: name, kind: "other" };
}

export function parseArgs(text) {
  if (!text) return null;
  try {
    const v = JSON.parse(text);
    return v && typeof v === "object" ? v : null;
  } catch {
    return null;
  }
}

export function getRelPath(p) {
  return relPath(p, state.workspace);
}

export function targetFromArgs(args, name) {
  if (!args) return "";
  for (const k of toolMeta(name).targetKeys || TARGET_KEYS) {
    const value = args[k];
    if ((name === "write_plan" && k === "items" || name === "update_task_statuses" && k === "updates") && Array.isArray(value)) {
      return `${value.length} 个任务`;
    }
    if (!["string", "number", "boolean"].includes(typeof value) || value === "") continue;
    const v = String(value).split(/\r?\n/)[0];
    if (k === "path") return args.scope === "knowledge" ? `知识库/${v}` : getRelPath(v);
    return args.scope === "knowledge" ? `知识库 · ${v}` : v;
  }
  return args.scope === "knowledge" ? "知识库" : "";
}

export function targetFromPartial(text, name) {
  const args = parseArgs(text);
  if (args) return targetFromArgs(args, name);
  const partial = Object.create(null);
  let depth = 0;
  const tokens = /"((?:[^"\\]|\\.)*)"\s*:\s*"((?:[^"\\]|\\.)*)"|"(?:[^"\\]|\\.)*"|[{}[\]]/g;
  for (const m of String(text || "").matchAll(tokens)) {
    if (m[0] === "{" || m[0] === "[") depth++;
    else if (m[0] === "}" || m[0] === "]") depth--;
    if (depth !== 1 || m[1] === undefined) continue;
    try {
      const key = JSON.parse(`"${m[1]}"`);
      partial[key] = JSON.parse(`"${m[2]}"`);
    } catch {
      continue;
    }
  }
  return targetFromArgs(partial, name);
}

export function diffStats(diff) {
  let add = 0;
  let del = 0;
  for (const line of String(diff).split("\n")) {
    if (line.startsWith("+") && !line.startsWith("+++")) add++;
    else if (line.startsWith("-") && !line.startsWith("---")) del++;
  }
  return { add, del };
}

export function statsFromArgs(name, args) {
  if (!args) return null;
  if (name === "write_file" && typeof args.content === "string") {
    return { add: args.content.replace(/\n$/, "").split("\n").length, del: 0 };
  }
  if (name === "edit_file" && typeof args.old_text === "string" && typeof args.new_text === "string") {
    const a = args.old_text.split("\n");
    const b = args.new_text.split("\n");
    let p = 0;
    while (p < a.length && p < b.length && a[p] === b[p]) p++;
    let s = 0;
    while (s < a.length - p && s < b.length - p && a[a.length - 1 - s] === b[b.length - 1 - s]) s++;
    return { add: b.length - p - s, del: a.length - p - s };
  }
  return null;
}

export function isFileTool(name) {
  const kind = toolMeta(name).kind;
  return kind === "read" || kind === "edit";
}

export function renderTarget(card) {
  const t = card.targetEl;
  t.textContent = "";
  t.title = card.target;
  if (!isFileTool(card.name) || !card.target) {
    t.textContent = card.target;
    return;
  }
  t.classList.add("file");
  const { dir, name } = splitPath(card.target);
  t.appendChild(el("span", "tt-name", name));
  if (dir) t.appendChild(el("span", "tt-meta", dir));
  if (card.lineCount) t.appendChild(el("span", "tt-meta", `${card.lineCount} 行`));
}

export function setToolTarget(card, target) {
  card.target = target;
  renderTarget(card);
}

export function setToolStats(card, stats) {
  card.statsEl.textContent = "";
  if (!stats) return;
  if (stats.add) card.statsEl.appendChild(el("span", "stat-add", `+${stats.add}`));
  if (stats.del) card.statsEl.appendChild(el("span", "stat-del", `−${stats.del}`));
}

export function setToolStatus(card, status) {
  card.error = status === "error";
  card.status.className = "tool-status " + status;
  card.status.textContent = status === "error" ? "失败" : "";
  card.el.classList.toggle("error", card.error);
}

/* ================= 折叠动效 ================= */

/** 折叠时长随内容高度自适应，由共享 ResizeObserver 写入 CSS 变量 */
function applyFoldDuration(host, h) {
  host.style.setProperty("--fold-duration", Math.round(Math.min(Math.max(180, 150 + h * 1.3), 650)) + "ms");
}

const foldObserver = new ResizeObserver((entries) => {
  for (const entry of entries) {
    const host = entry.target.closest(".work-group, .turn-fold");
    if (!host) continue;
    applyFoldDuration(host, entry.target.offsetHeight);
  }
});

function bindFoldDynamics(host, content) {
  applyFoldDuration(host, content.offsetHeight);
  foldObserver.observe(content);
}

/* ================= 思考区与工作分组 ================= */

export function thinkingNode(text) {
  const wrap = el("div", "thinking");
  const header = el("div", "thinking-header");
  header.appendChild(icon("bulb", "thinking-icon"));
  header.appendChild(el("span", "thinking-title", "思考过程"));
  header.appendChild(icon("chevron", "caret"));
  const body = el("div", "thinking-body");
  const clip = el("div", "fold-clip");
  clip.appendChild(el("div", "thinking-text", text));
  body.appendChild(clip);
  header.onclick = () => wrap.classList.toggle("expanded");
  wrap.appendChild(header);
  wrap.appendChild(body);
  return wrap;
}

export function workGroupNode() {
  const wrap = el("div", "work-group");
  const header = el("div", "work-header");
  header.appendChild(icon("work", "work-icon"));
  const summary = el("span", "work-summary", "工作过程");
  header.appendChild(summary);
  header.appendChild(icon("chevron", "caret"));
  const body = el("div", "work-body");
  const clip = el("div", "fold-clip");
  const items = el("div", "work-items");
  clip.appendChild(items);
  body.appendChild(clip);
  header.onclick = () => wrap.classList.toggle("expanded");
  wrap.appendChild(header);
  wrap.appendChild(body);
  bindFoldDynamics(wrap, items);
  return { el: wrap, body: items, summary, items: [] };
}

export function addToGroup(group, item) {
  group.items.push(item);
  if (!(item instanceof Element)) item.group = group;
  group.body.appendChild(item instanceof Element ? item : item.el);
  refreshWorkSummary(group);
}

/** 汇总条目 {thought?, kind?, fileKey?, error?} → 文案与失败数 */
function workSummaryText(items) {
  const files = { edit: new Set(), read: new Set() };
  const counts = { list: 0, search: 0, command: 0, other: 0 };
  let errors = 0;
  let thoughts = 0;
  for (const item of items) {
    if (item.thought) {
      thoughts++;
      continue;
    }
    if (item.error) errors++;
    if (files[item.kind]) files[item.kind].add(item.fileKey);
    else counts[item.kind]++;
  }
  const parts = [];
  if (files.edit.size) parts.push(`编辑 ${files.edit.size} 个文件`);
  if (files.read.size) parts.push(`读取 ${files.read.size} 个文件`);
  if (counts.list) parts.push(`查看 ${counts.list} 个目录`);
  if (counts.search) parts.push(`搜索 ${counts.search} 次`);
  if (counts.command) parts.push(`运行 ${counts.command} 条命令`);
  if (counts.other) parts.push(`调用 ${counts.other} 个工具`);
  let text = parts.join("、") || (thoughts ? "思考过程" : "工作过程");
  if (errors) text += `，${errors} 项失败`;
  return { text, errors };
}

export function refreshWorkSummary(group) {
  const items = group.items.map((item) =>
    item instanceof Element
      ? { thought: true }
      : { kind: toolMeta(item.name).kind, fileKey: item.target || item.id, error: item.error });
  const { text, errors } = workSummaryText(items);
  group.summary.textContent = text;
  group.el.classList.toggle("has-error", errors > 0);
}

/* ================= 回合折叠 ================= */

function summarizeProcess(nodes) {
  const items = [];
  for (const node of nodes) {
    node.querySelectorAll(".tool-card").forEach((card, i) => {
      const name = card.querySelector(".tool-verb")?.title || "";
      items.push({
        kind: toolMeta(name).kind,
        fileKey: card.querySelector(".tool-target")?.title || `${name}#${i}`,
        error: card.classList.contains("error"),
      });
    });
    node.querySelectorAll(".thinking").forEach(() => items.push({ thought: true }));
  }
  return workSummaryText(items).text;
}

/**
 * 把一轮回答的过程内容（除最终回答外的全部节点）收进折叠条
 *
 * @param {HTMLElement} content 回合内容容器（.turn-content）
 * @param {boolean} [animate] true 时先展开再收起，播放一次折叠动画
 */
export function foldTurnProcess(content, animate = false) {
  if (!content) return;
  const children = Array.from(content.children);
  let lastAnswer = -1;
  children.forEach((node, i) => {
    if (node.classList.contains("msg") && node.classList.contains("assistant")) lastAnswer = i;
  });
  const process = (lastAnswer >= 0 ? children.slice(0, lastAnswer) : children)
    .filter((node) => node.id !== "pending-indicator" && !node.classList.contains("turn-fold"));
  if (!process.length) return;
  const fold = el("div", "turn-fold");
  const head = el("div", "turn-fold-head");
  head.appendChild(el("span", "turn-fold-summary", summarizeProcess(process)));
  head.appendChild(icon("chevron", "caret"));
  const wrap = el("div", "turn-fold-wrap");
  const clip = el("div", "fold-clip");
  const inner = el("div", "turn-fold-content");
  for (const node of process) inner.appendChild(node);
  clip.appendChild(inner);
  wrap.appendChild(clip);
  fold.appendChild(head);
  fold.appendChild(wrap);
  content.insertBefore(fold, lastAnswer >= 0 ? children[lastAnswer] : null);
  head.onclick = () => fold.classList.toggle("expanded");
  bindFoldDynamics(fold, inner);
  if (animate) {
    fold.classList.add("expanded");
    requestAnimationFrame(() => requestAnimationFrame(() => fold.classList.remove("expanded")));
  }
}

export function colorizeDiff(pre, diff) {
  pre.textContent = "";
  for (const line of String(diff).split("\n")) {
    const span = el("span", null, line + "\n");
    if (line.startsWith("+") && !line.startsWith("+++")) span.className = "diff-add";
    else if (line.startsWith("-") && !line.startsWith("---")) span.className = "diff-del";
    pre.appendChild(span);
  }
}

/* ================= 文件视图与高亮 ================= */

export const FILE_VIEW_MAX_ROWS = 400;

export function parseUnifiedDiff(diff) {
  const rows = [];
  let oldNo = 1;
  let newNo = 1;
  for (const line of String(diff).split("\n")) {
    if (line === "" || line.startsWith("---") || line.startsWith("+++") || line.startsWith("\\")) continue;
    const hunk = /^@@ -(\d+)(?:,\d+)? \+(\d+)/.exec(line);
    if (hunk) {
      oldNo = +hunk[1] || 1;
      newNo = +hunk[2] || 1;
      if (rows.length) rows.push({ type: "gap" });
      continue;
    }
    const text = line.slice(1);
    if (line[0] === "+") rows.push({ type: "add", no: newNo++, text });
    else if (line[0] === "-") rows.push({ type: "del", no: oldNo++, text });
    else {
      rows.push({ type: "ctx", no: newNo++, text });
      oldNo++;
    }
  }
  return rows;
}

export function lineDiff(oldText, newText) {
  const a = oldText.split(/\r?\n/);
  const b = newText.split(/\r?\n/);
  if (a.length * b.length > 250000) {
    return [
      ...a.map((text, i) => ({ type: "del", no: i + 1, text })),
      ...b.map((text, i) => ({ type: "add", no: i + 1, text })),
    ];
  }
  const dp = Array.from({ length: a.length + 1 }, () => new Uint16Array(b.length + 1));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const rows = [];
  let i = 0;
  let j = 0;
  while (i < a.length || j < b.length) {
    if (i < a.length && j < b.length && a[i] === b[j]) {
      rows.push({ type: "ctx", no: j + 1, text: b[j] });
      i++;
      j++;
    } else if (i < a.length && (j >= b.length || dp[i + 1][j] >= dp[i][j + 1])) {
      rows.push({ type: "del", no: i + 1, text: a[i] });
      i++;
    } else {
      rows.push({ type: "add", no: j + 1, text: b[j] });
      j++;
    }
  }
  return rows;
}

export function parseReadResult(text) {
  const rows = [];
  let note = "";
  for (const line of String(text).split(/\r?\n/)) {
    const m = /^\s*(\d+)\t(.*)$/.exec(line);
    if (m) rows.push({ type: "ctx", no: +m[1], text: m[2] });
    else if (/^\.\.\. \((\d+) more lines/.test(line)) note = `还有 ${/\d+/.exec(line)[0]} 行未读取`;
    else if (line === "(empty file)") note = "空文件";
  }
  return { rows, note };
}

export function fileRowsFromArgs(name, args) {
  if (!args) return null;
  if (name === "edit_file" && typeof args.old_text === "string" && typeof args.new_text === "string") {
    return lineDiff(args.old_text, args.new_text);
  }
  if (name === "write_file" && typeof args.content === "string") {
    return args.content.replace(/\n$/, "").split("\n").map((text, i) => ({ type: "add", no: i + 1, text }));
  }
  return null;
}

export { hlLang, highlightLine };

export function renderFileView(card, rows, note) {
  const view = card.fileView;
  view.textContent = "";
  const mode = card.name === "read_file" ? "read" : "diff";
  view.className = "file-view mode-" + mode;

  const head = el("div", "fv-head");
  const { dir, name } = splitPath(card.target || "");
  if (mode === "diff" && dir) head.appendChild(el("span", "fv-dir", dir + "/"));
  head.appendChild(el("span", "fv-name", name));
  if (mode === "diff") {
    const stats = card.statsEl.cloneNode(true);
    stats.className = "tool-stats fv-stats";
    head.appendChild(stats);
  }
  head.appendChild(el("span", "spacer"));
  const copy = el("button", "fv-copy");
  copy.type = "button";
  copy.title = "复制";
  copy.setAttribute("aria-label", "复制代码");
  copy.appendChild(icon("copy", "icon-copy"));
  copy.appendChild(icon("tick", "icon-tick"));
  const shown = rows.slice(0, FILE_VIEW_MAX_ROWS);
  copy.onclick = (e) => {
    e.stopPropagation();
    const text = shown.filter((r) => r.type !== "del" && r.type !== "gap").map((r) => r.text).join("\n");
    navigator.clipboard.writeText(text).then(() => {
      copy.classList.add("copied");
      copy.title = "已复制";
      clearTimeout(copy._timer);
      copy._timer = setTimeout(() => {
        copy.classList.remove("copied");
        copy.title = "复制";
      }, 1800);
    }).catch(() => {});
  };
  head.appendChild(copy);
  view.appendChild(head);

  const lang = hlLang(card.target);
  const body = el("div", "fv-body");
  const lines = el("div", "fv-lines");
  for (const row of shown) {
    const r = el("div", "fv-row " + row.type);
    if (mode === "read") {
      r.appendChild(el("span", "fv-no", row.type === "gap" ? "⋯" : String(row.no)));
    } else {
      r.appendChild(el("span", "fv-sign", row.type === "add" ? "+" : row.type === "del" ? "−" : row.type === "gap" ? "⋯" : ""));
    }
    const textEl = el("span", "fv-text");
    if (row.type !== "gap") textEl.appendChild(highlightLine(row.text || " ", lang));
    r.appendChild(textEl);
    lines.appendChild(r);
  }
  body.appendChild(lines);
  view.appendChild(body);
  const more = rows.length > FILE_VIEW_MAX_ROWS ? `还有 ${rows.length - FILE_VIEW_MAX_ROWS} 行未显示` : "";
  if (note || more) view.appendChild(el("div", "fv-note", note || more));
  card.el.classList.add("has-file");
}

export function applyReadMeta(card, resultText) {
  const m = /^\[[^\]|]*\|\s*(\d+) lines/.exec(String(resultText || ""));
  if (m) {
    card.lineCount = +m[1];
    renderTarget(card);
  }
}

export function buildToolCard(name, id) {
  const meta = toolMeta(name);
  const card = el("div", "tool-card");
  const header = el("div", "tool-header");
  const pending = el("span", "tool-pending-label hidden", "等待批准");
  const verb = el("span", "tool-verb", meta.verb);
  verb.title = name;
  const targetEl = el("span", "tool-target");
  const statsEl = el("span", "tool-stats");
  const status = el("span", "tool-status running");
  header.appendChild(icon(meta.icon, "tool-icon"));
  header.appendChild(verb);
  header.appendChild(targetEl);
  header.appendChild(statsEl);
  header.appendChild(pending);
  header.appendChild(status);
  header.appendChild(icon("chevron", "caret"));
  const body = el("div", "tool-body");
  const clip = el("div", "fold-clip");
  const content = el("div", "tool-content");
  const args = el("pre", "tool-args");
  const diff = el("pre", "tool-diff diff hidden");
  const result = el("pre", "tool-result hidden");
  const fileView = el("div", "file-view hidden");
  content.appendChild(fileView);
  content.appendChild(args);
  content.appendChild(diff);
  content.appendChild(result);
  clip.appendChild(content);
  body.appendChild(clip);
  card.appendChild(header);
  card.appendChild(body);
  header.onclick = () => card.classList.toggle("expanded");
  return {
    el: card,
    id,
    name,
    target: "",
    error: false,
    status,
    args,
    diff,
    result,
    pending,
    body,
    targetEl,
    statsEl,
    fileView,
  };
}

export function historyToolCard(name, argsText, resultText, isError) {
  const card = buildToolCard(name);
  card.args.textContent = argsText || "";
  const args = parseArgs(argsText);
  setToolTarget(card, targetFromArgs(args, name));
  setToolStats(card, statsFromArgs(name, args));
  const rows = fileRowsFromArgs(name, args);
  if (rows) {
    renderFileView(card, rows);
  } else if (name === "read_file" && resultText && !isError) {
    applyReadMeta(card, resultText);
    const read = parseReadResult(resultText);
    if (read.rows.length || read.note) renderFileView(card, read.rows, read.note);
  }
  if (resultText !== null && resultText !== undefined) {
    card.result.textContent = resultText;
    card.result.classList.remove("hidden");
    card.result.classList.toggle("error", !!isError);
    setToolStatus(card, isError ? "error" : "done");
  } else {
    card.result.remove();
    setToolStatus(card, "done");
  }
  return card;
}
