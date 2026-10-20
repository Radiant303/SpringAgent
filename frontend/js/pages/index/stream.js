/**
 * 实时事件流调度与处理
 */

import { $, el, hidePending, appendKeepingBottom, icon } from "../../utils/dom.js";
import { getToken } from "../../api/auth.js";
import { rpc } from "../../api/rpc.js";
import { enhanceCodeBlocks, setMarkdown } from "../../utils/markdown.js";
import { SproutManager } from "../../components/sprout.js";
import {
  state,
  runOf,
  isBusy,
  setBusy,
  releaseBusy,
  ensureAssistant,
  ensureWork,
  setActive,
  closeWork,
  foldTurnGroups,
  updateLatestPet,
  maybeScroll,
  discardEmptyTurn,
} from "./state.js";
import {
  parseArgs,
  targetFromArgs,
  targetFromPartial,
  setToolTarget,
  setToolStats,
  statsFromArgs,
  fileRowsFromArgs,
  renderFileView,
  colorizeDiff,
  diffStats,
  parseUnifiedDiff,
  setToolStatus,
  applyReadMeta,
  parseReadResult,
  refreshWorkSummary,
  buildToolCard,
  thinkingNode,
  addToGroup,
  addSystem,
} from "./chatView.js";
import { updateInputState } from "./input.js";
import { refreshSessionList } from "./sessions.js";
import { notifyStorageChanged } from "../../utils/storage.js";

export const USAGE_RING_LEN = 56.5;

const FLUSH_INTERVAL_MS = 16;

let flushTimer = null;
let pendingScroll = false;
let compactionHideTimer = null;
const pendingAnswers = new Map();
const pendingThinking = new Map();
const pendingArgs = new Map();

function showCompactionStatus(text, done = false) {
  const box = $("context-status");
  const label = $("context-status-text");
  if (!box || !label) return;
  if (compactionHideTimer) {
    clearTimeout(compactionHideTimer);
    compactionHideTimer = null;
  }
  label.textContent = text;
  box.classList.toggle("done", done);
  box.classList.remove("hidden");
}

function hideCompactionStatus() {
  if (compactionHideTimer) {
    clearTimeout(compactionHideTimer);
    compactionHideTimer = null;
  }
  $("context-status")?.classList.add("hidden");
}

function scheduleFlush() {
  if (flushTimer !== null) return;
  flushTimer = setTimeout(flushPending, FLUSH_INTERVAL_MS);
}

/** 把缓冲的增量一次性写入 DOM */
export function flushPending() {
  if (flushTimer !== null) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  for (const [body, text] of pendingThinking) appendKeepingBottom(body, text);
  pendingThinking.clear();
  for (const [toolCallId, chunk] of pendingArgs) {
    const card = state.tools.get(toolCallId);
    if (!card) continue;
    appendKeepingBottom(card.args, chunk);
    applyToolArgs(card);
  }
  pendingArgs.clear();
  for (const [assistant, text] of pendingAnswers) {
    assistant.text += text;
    setMarkdown(assistant.answer, assistant.text, false);
  }
  pendingAnswers.clear();
  if (pendingScroll) {
    pendingScroll = false;
    maybeScroll();
  }
}

export function discardPending() {
  if (flushTimer !== null) {
    clearTimeout(flushTimer);
    flushTimer = null;
  }
  pendingThinking.clear();
  pendingArgs.clear();
  pendingAnswers.clear();
  pendingScroll = false;
}

function applyToolArgs(card) {
  const text = card.args.textContent;
  const args = parseArgs(text);
  const target = args ? targetFromArgs(args, card.name) : targetFromPartial(text, card.name);
  if (target !== card.target) {
    setToolTarget(card, target);
    if (state.work) refreshWorkSummary(state.work);
  }
  if (args && !card.hasDiff) {
    setToolStats(card, statsFromArgs(card.name, args));
    const rows = fileRowsFromArgs(card.name, args);
    if (rows) renderFileView(card, rows);
  }
}

function settleFinishedTools() {
  for (const card of state.tools.values()) {
    if (!card.finishDeferred) continue;
    card.finishDeferred = false;
    setToolStatus(card, "done");
  }
}

export async function refreshPoints() {
  const value = $("points-value");
  const token = getToken();
  if (!value || !token) return;
  const settle = () => {
    if (value.querySelector(".sk")) value.textContent = "积分 -";
  };
  try {
    const r = await fetch("/api/billing/me", { headers: { Authorization: "Bearer " + token } });
    if (!r.ok) {
      settle();
      return;
    }
    const data = (await r.json()).data;
    const v = Number(data?.balance ?? 0);
    value.textContent = "积分 " + parseFloat(v.toFixed(6)).toLocaleString(undefined, { maximumFractionDigits: 6 });
    const card = $("points-card");
    if (card) card.classList.toggle("negative", v < 0);
  } catch (e) {
    settle();
  }
}

export function updateUsage(tokens) {
  const model = state.models.find((m) => m.id === state.model);
  const max = model ? model.maxContextSize : 0;
  const prog = $("usage-ring-prog");
  const tip = $("usage-tip");
  if (!prog || !tip) return;
  if (!max) {
    prog.style.strokeDashoffset = String(USAGE_RING_LEN);
    tip.textContent = `已使用 ${(tokens / 1000).toFixed(1)}k tokens`;
    return;
  }
  const pct = Math.min(tokens / max, 1);
  prog.style.strokeDashoffset = String(USAGE_RING_LEN * (1 - pct));
  tip.textContent = `使用 ${(tokens / 1000).toFixed(1)}k / ${Math.round(max / 1000)}k tokens (${Math.round(pct * 100)}%)`;
}

export async function loadPlan(generation = state.viewGeneration) {
  const sid = state.sessionId;
  const workId = state.workId;
  const socket = rpc.ws;
  const isCurrent = () => sid === state.sessionId && generation === state.viewGeneration
    && workId === state.workId && socket === rpc.ws && state.connected;
  if (!sid) {
    if (isCurrent()) renderPlan([]);
    return;
  }
  try {
    const r = await rpc.request("session/plan", { sessionId: sid });
    if (isCurrent()) renderPlan(r.items);
  } catch {}
}

export function renderPlan(items) {
  const dock = $("plan-dock");
  const panel = $("plan-panel");
  const list = $("plan-items");
  if (!dock || !panel || !list) return;
  if (!items || !items.length) {
    dock.classList.add("hidden");
    return;
  }
  dock.classList.remove("hidden");
  list.textContent = "";
  let done = 0;
  for (const item of items) {
    const status = item.status || "pending";
    if (status === "completed") done++;
    const li = el("li", status);
    li.appendChild(status === "completed" ? icon("planCheck", "plan-mark") : el("span", "plan-mark"));
    const text = status === "in_progress" && item.active_form ? item.active_form : item.content || "";
    li.appendChild(el("span", "plan-text", text));
    list.appendChild(li);
  }
  const label = `${done}/${items.length}`;
  if ($("plan-progress")) $("plan-progress").textContent = label;
  if ($("plan-pill-count")) $("plan-pill-count").textContent = label;
  if ($("plan-pill")) $("plan-pill").setAttribute("aria-label", `当前进度 ${label}`);
  panel.classList.toggle("all-done", done === items.length);
}

export function seqAfter(a, b) {
  const [am, as] = a.split("-").map(Number);
  const [bm, bs] = b.split("-").map(Number);
  return am > bm || (am === bm && as > bs);
}

export function onSessionEvent(sid, event, seq) {
  if (!sid || state.deletedSessions.has(sid)) return;
  if (seq) {
    const last = state.lastSeq[sid];
    if (last && !seqAfter(seq, last)) return;
    state.lastSeq[sid] = seq;
  }
  if (event.kind === "storage_updated") {
    notifyStorageChanged();
    return;
  }
  if (event.kind === "turn_finished") notifyStorageChanged();
  if (sid !== state.sessionId || !state.sessionReady) {
    if (event.kind === "turn_finished") {
      releaseBusy(sid);
      runOf(sid).unread = true;
      refreshSessionList();
    } else {
      const wasBusy = isBusy(sid);
      setBusy(sid, true);
      if (!wasBusy) refreshSessionList();
    }
    return;
  }
  if (event.kind !== "turn_finished") {
    const wasBusy = isBusy(sid);
    setBusy(sid, true);
    if (!wasBusy) {
      updateInputState();
      refreshSessionList();
    }
  }
  hidePending();
  switch (event.kind) {
    case "text_delta": {
      settleFinishedTools();
      const a = ensureAssistant();
      if (!a.text && !pendingAnswers.has(a)) setActive(null);
      pendingAnswers.set(a, (pendingAnswers.get(a) || "") + event.text);
      pendingScroll = true;
      scheduleFlush();
      break;
    }
    case "thinking_delta": {
      settleFinishedTools();
      const group = ensureWork();
      if (!state.thinking) {
        state.thinking = thinkingNode("");
        addToGroup(group, state.thinking);
        setActive(state.thinking);
      }
      const body = state.thinking.querySelector(".thinking-body");
      pendingThinking.set(body, (pendingThinking.get(body) || "") + event.text);
      pendingScroll = true;
      scheduleFlush();
      break;
    }
    case "tool_started": {
      flushPending();
      settleFinishedTools();
      const group = ensureWork();
      state.thinking = null;
      const card = buildToolCard(event.tool_name, event.tool_call_id);
      state.tools.set(event.tool_call_id, card);
      addToGroup(group, card);
      setActive(card.el);
      maybeScroll();
      break;
    }
    case "tool_args_delta": {
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        pendingArgs.set(event.tool_call_id, (pendingArgs.get(event.tool_call_id) || "") + event.args_chunk);
        pendingScroll = true;
        scheduleFlush();
      }
      break;
    }
    case "tool_diff": {
      flushPending();
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.hasDiff = true;
        card.diff.classList.remove("hidden");
        colorizeDiff(card.diff, event.diff);
        setToolStats(card, diffStats(event.diff));
        const rows = parseUnifiedDiff(event.diff);
        if (rows.length) renderFileView(card, rows);
      }
      break;
    }
    case "tool_pending": {
      flushPending();
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.pending.textContent = event.label || "等待批准";
        card.pending.classList.remove("hidden");
      }
      break;
    }
    case "tool_finished": {
      flushPending();
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.pending.classList.add("hidden");
        if (event.is_error) setToolStatus(card, "error");
        else card.finishDeferred = true;
        card.result.textContent = event.result;
        card.result.classList.remove("hidden");
        card.result.classList.toggle("error", !!event.is_error);
        if (["write_file", "edit_file", "delete_path", "create_directory"].includes(card.name)) {
          notifyStorageChanged();
        }
        if (card.name === "read_file" && !event.is_error) {
          applyReadMeta(card, event.result);
          const read = parseReadResult(event.result);
          if (read.rows.length || read.note) renderFileView(card, read.rows, read.note);
        }
        if (card.group) refreshWorkSummary(card.group);
        maybeScroll();
      }
      break;
    }
    case "usage": {
      updateUsage(event.context_tokens);
      break;
    }
    case "plan_updated": {
      renderPlan(event.items);
      break;
    }
    case "teaching_updated": {
      const unit = event.unit || {};
      addSystem(`📘 教学单元更新：${unit.title || unit.slug || ""}（${unit.status || ""}）`);
      break;
    }
    case "compaction_started": {
      showCompactionStatus("正在压缩上下文…");
      break;
    }
    case "compaction": {
      showCompactionStatus("上下文已压缩", true);
      compactionHideTimer = setTimeout(hideCompactionStatus, 1500);
      break;
    }
    case "turn_finished": {
      flushPending();
      finishTurn(event);
      break;
    }
  }
}

export function finishTurn(event) {
  hideCompactionStatus();
  const groups = new Set();
  for (const card of state.tools.values()) {
    card.pending.classList.add("hidden");
    if (card.status.classList.contains("running")) {
      setToolStatus(card, "done");
    }
    if (card.group) groups.add(card.group);
  }
  groups.forEach(refreshWorkSummary);
  setActive(null);
  closeWork();
  foldTurnGroups();
  if (state.assistant) enhanceCodeBlocks(state.assistant.answer);
  state.assistant = null;
  state.tools.clear();
  discardEmptyTurn();
  state.currentTurn = null;
  updateLatestPet();
  if (event.cancelled) addSystem("已中断，可继续输入");
  else if (event.error) addSystem("❌ 运行出错：" + event.error, true);
  releaseBusy(state.sessionId);
  updateInputState();
  notifyStorageChanged();
  setTimeout(refreshPoints, 1500);
  refreshSessionList();
}
