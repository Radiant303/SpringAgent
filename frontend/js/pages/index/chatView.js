/**
 * 消息节点、工具卡片、Diff 与文件视图
 */

import { $, el, icon } from "../../utils/dom.js";
import { setMarkdown } from "../../utils/markdown.js";
import { relPath, splitPath } from "../../utils/format.js";
import { state, closeWork, maybeScroll } from "./state.js";

/* ================= 基础消息节点 ================= */

export function userNode(text) {
  return el("div", "msg user", text);
}

export function noticeNode(text) {
  return el("div", "msg notice", text);
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
  edit_file: { icon: "edit", verb: "编辑", kind: "edit" },
  write_file: { icon: "edit", verb: "写入", kind: "edit" },
  edit_knowledge: { icon: "book", verb: "编辑知识", kind: "other" },
  create_directory: { icon: "folder", verb: "创建目录", kind: "other" },
  read_file: { icon: "file", verb: "读取", kind: "read" },
  file_info: { icon: "file", verb: "查看", kind: "read" },
  list_directory: { icon: "folder", verb: "列出", kind: "list" },
  search_files: { icon: "search", verb: "搜索", kind: "search" },
  find_files: { icon: "search", verb: "查找", kind: "search" },
  run_command: { icon: "terminal", verb: "运行", kind: "command" },
  start_command: { icon: "terminal", verb: "后台运行", kind: "command" },
  check_command: { icon: "terminal", verb: "查看命令", kind: "other" },
  stop_command: { icon: "terminal", verb: "停止命令", kind: "other" },
  read_knowledge: { icon: "book", verb: "查阅知识", kind: "other" },
  read_index_knowledge: { icon: "book", verb: "查阅知识索引", kind: "other" },
  ask_user: { icon: "question", verb: "提问", kind: "other" },
};

export const TARGET_KEYS = ["path", "command", "pattern", "question", "command_id", "type"];

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

export function targetFromArgs(args) {
  if (!args) return "";
  for (const k of TARGET_KEYS) {
    if (args[k] !== undefined && args[k] !== null && args[k] !== "") {
      const v = String(args[k]);
      return k === "path" ? getRelPath(v) : v.split("\n")[0];
    }
  }
  return "";
}

export function targetFromPartial(text) {
  const m = /"(path|command|pattern|question|command_id)"\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(text);
  if (!m) return "";
  let v;
  try {
    v = JSON.parse(`"${m[2]}"`);
  } catch {
    v = m[2];
  }
  return m[1] === "path" ? getRelPath(v) : v.split("\n")[0];
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
  if (name === "edit_knowledge" && typeof args.diff === "string") return diffStats(args.diff);
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

/* ================= 思考区与工作分组 ================= */

export function thinkingNode(text) {
  const wrap = el("div", "thinking");
  const header = el("div", "thinking-header");
  header.appendChild(icon("bulb", "thinking-icon"));
  header.appendChild(el("span", "thinking-title", "思考过程"));
  header.appendChild(icon("chevron", "caret"));
  const body = el("div", "thinking-body", text);
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
  header.onclick = () => wrap.classList.toggle("expanded");
  wrap.appendChild(header);
  wrap.appendChild(body);
  return { el: wrap, body, summary, items: [] };
}

export function addToGroup(group, item) {
  group.items.push(item);
  if (!(item instanceof Element)) item.group = group;
  group.body.appendChild(item instanceof Element ? item : item.el);
  refreshWorkSummary(group);
}

export function refreshWorkSummary(group) {
  const files = { edit: new Set(), read: new Set() };
  const counts = { list: 0, search: 0, command: 0, other: 0 };
  let errors = 0;
  let thoughts = 0;
  for (const item of group.items) {
    if (item instanceof Element) {
      thoughts++;
      continue;
    }
    const kind = toolMeta(item.name).kind;
    if (item.error) errors++;
    if (files[kind]) files[kind].add(item.target || item.id);
    else counts[kind]++;
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
  group.summary.textContent = text;
  group.el.classList.toggle("has-error", errors > 0);
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
  if (name === "edit_knowledge" && typeof args.diff === "string") return parseUnifiedDiff(args.diff);
  return null;
}

export const HL_KEYWORDS = new Set((
  "const let var function return if else for while do switch case break continue new class extends " +
  "import from export default async await try catch finally throw typeof instanceof in of this null " +
  "undefined true false def self None True False elif lambda pass with as yield raise and or not is " +
  "public private protected static void int long boolean package interface implements"
).split(" "));

export function hlLang(path) {
  const ext = (/\.([a-z0-9]+)$/i.exec(path || "") || [])[1];
  if (!ext) return "plain";
  const e = ext.toLowerCase();
  if (["html", "htm", "xml", "svg", "vue"].includes(e)) return "markup";
  if (["css", "scss", "less"].includes(e)) return "css";
  if (["py", "toml", "yaml", "yml", "sh", "ini"].includes(e)) return "hash";
  if (["js", "ts", "jsx", "tsx", "mjs", "java", "go", "rs", "c", "cpp", "h", "kt", "json"].includes(e)) return "c";
  return "plain";
}

export const HL_RULES = {
  c: /(\/\/.*$|\/\*.*?\*\/)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`)|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_$][\w$]*)\b/g,
  hash: /(#.*$)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_][\w]*)\b/g,
  css: /(\/\*.*?\*\/)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(-?\d+(?:\.\d+)?(?:px|em|rem|%|s|ms|vh|vw|deg)?)\b|([#.][\w-]+(?=[^;{}]*\{))/g,
  markup: /(<!--.*?-->)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(<\/?[\w-]+|\/?>|<!DOCTYPE)|(\s[\w-]+(?==))/gi,
};

export function highlightLine(text, lang) {
  const frag = document.createDocumentFragment();
  const re = HL_RULES[lang];
  if (!re) {
    frag.appendChild(document.createTextNode(text));
    return frag;
  }
  re.lastIndex = 0;
  let last = 0;
  let m;
  while ((m = re.exec(text))) {
    if (m[0] === "") {
      re.lastIndex++;
      continue;
    }
    let cls = null;
    if (m[1]) cls = "hl-comment";
    else if (m[2]) cls = "hl-string";
    else if (m[3]) cls = lang === "markup" ? "hl-tag" : "hl-number";
    else if (m[4]) {
      if (lang === "markup") cls = "hl-attr";
      else if (lang === "css") cls = "hl-selector";
      else if (HL_KEYWORDS.has(m[4])) cls = "hl-keyword";
    }
    if (!cls) continue;
    if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)));
    frag.appendChild(el("span", cls, m[0]));
    last = m.index + m[0].length;
  }
  if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)));
  return frag;
}

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
  copy.appendChild(icon("copy"));
  const shown = rows.slice(0, FILE_VIEW_MAX_ROWS);
  copy.onclick = (e) => {
    e.stopPropagation();
    const text = shown.filter((r) => r.type !== "del" && r.type !== "gap").map((r) => r.text).join("\n");
    navigator.clipboard.writeText(text).then(() => {
      copy.textContent = "";
      copy.appendChild(icon("tick"));
      setTimeout(() => {
        copy.textContent = "";
        copy.appendChild(icon("copy"));
      }, 1200);
    });
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
  const args = el("pre", "tool-args");
  const diff = el("pre", "tool-diff diff hidden");
  const result = el("pre", "tool-result hidden");
  const fileView = el("div", "file-view hidden");
  body.appendChild(fileView);
  body.appendChild(args);
  body.appendChild(diff);
  body.appendChild(result);
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
  setToolTarget(card, targetFromArgs(args));
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
