/**
 * 聊天页面全局状态与回合容器管理
 */

import { $, el, scrollToBottom, markScrolling, hidePending } from "../../utils/dom.js";
import { SproutManager } from "../../components/sprout.js";
import { workGroupNode, refreshWorkSummary, answerNode } from "./chatView.js";

export const AUTO_SOURCES = new Set(["file_monitor"]);
export const runs = {};

export function runOf(sid) {
  if (!sid) return null;
  return runs[sid] ||= { busy: false, attention: null, unread: false };
}

export function isBusy(sid) {
  return !!(sid && runs[sid]?.busy);
}

export function setBusy(sid, value) {
  if (sid) runOf(sid).busy = !!value;
}

// 发送后引擎经 MQ 感知回合有延迟：保护窗内服务端 busy=false 不冲掉本地 busy
export function holdBusy(sid, ms) {
  const run = runOf(sid);
  if (run) run.busyHoldUntil = Date.now() + ms;
}

export function releaseBusy(sid) {
  const run = runs[sid];
  if (!run) return;
  run.busy = false;
  run.busyHoldUntil = 0;
}

export function applyServerBusy(sid, busy) {
  const run = runOf(sid);
  if (!run) return;
  run.busy = !!busy || !!(run.busyHoldUntil && Date.now() < run.busyHoldUntil);
}

export const state = {
  workspace: null,
  models: [],
  model: null,
  effort: null,
  modelChanging: false,
  sessionId: null,
  deletedSessions: new Set(),
  workId: localStorage.getItem("sa.workId") || null,
  works: [],
  connected: false,
  sessionReady: false,
  viewGeneration: 0,
  assistant: null,      // 当前流式正文气泡 {el, answer, text}
  work: null,           // 当前「工作过程」分组（连续的思考 + 工具调用）
  turnGroups: [],       // 本轮已创建的分组：进行中平铺，本轮结束统一折叠
  thinking: null,       // 当前流式思考区 {el, body}
  active: null,         // 当前展开的那一项（思考区或工具卡片），渐进式收起用
  tools: new Map(),     // tool_call_id -> 卡片引用
  historyCursor: null,  // 继续向回翻页的 previousCursor
  historyHasMore: false,
  currentTurn: null,    // 当前助手回合容器 {el, content, petBox, canvas, pet}
  lastSeq: {},          // sessionId -> 事件流去重游标（已收到的最大条目 ID）
};

/* ================= 滚动与钉底状态 ================= */

export let stickToBottom = true;
export let hasNewBelow = false;
let smoothScrolling = false;
let smoothScrollCancel = null;

export function setStickToBottom(v) { stickToBottom = v; }
export function setHasNewBelow(v) { hasNewBelow = v; }

export function syncScrollBtn() {
  const box = $("messages");
  if (!box) return;
  const dist = box.scrollHeight - box.scrollTop - box.clientHeight;
  const dock = $("plan-dock");
  const panel = $("plan-panel");
  const cardOpen = dock && panel && !dock.classList.contains("hidden") && !panel.classList.contains("hidden");
  const show = !cardOpen && !stickToBottom && (dist > 320 || hasNewBelow);
  const btn = $("scroll-bottom");
  if (btn) btn.classList.toggle("hidden", !show);
}

export function updateStick() {
  if (smoothScrolling) return;
  const box = $("messages");
  if (!box) return;
  stickToBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 80;
  if (stickToBottom) hasNewBelow = false;
  syncScrollBtn();
}

export function maybeScroll(layoutOnly = false) {
  if (stickToBottom) {
    scrollToBottom();
    return;
  }
  if (!layoutOnly) hasNewBelow = true;
  syncScrollBtn();
}

export function smoothScrollToBottom() {
  const box = $("messages");
  if (!box) return;
  stickToBottom = true;
  hasNewBelow = false;
  syncScrollBtn();
  const start = box.scrollTop;
  const delta = box.scrollHeight - box.clientHeight - start;
  if (delta <= 0) return;
  if (smoothScrollCancel) smoothScrollCancel();
  const DURATION = 500;
  const t0 = performance.now();
  smoothScrolling = true;
  const cancel = () => {
    smoothScrolling = false;
    smoothScrollCancel = null;
    box.removeEventListener("wheel", cancel);
    box.removeEventListener("touchstart", cancel);
    updateStick();
  };
  smoothScrollCancel = cancel;
  box.addEventListener("wheel", cancel);
  box.addEventListener("touchstart", cancel);
  function step(now) {
    if (!smoothScrolling) return;
    const p = Math.min((now - t0) / DURATION, 1);
    box.scrollTop = start + delta * (1 - Math.pow(1 - p, 3));
    if (p < 1) {
      requestAnimationFrame(step);
      return;
    }
    cancel();
  }
  requestAnimationFrame(step);
}

/* ================= 助手回合与宠物 ================= */

export function createAssistantTurnNode(isDynamic = false) {
  const wrap = el("div", "assistant-turn");
  const petBox = el("div", "turn-pet");
  const canvas = el("canvas", "sprout-pet");
  petBox.appendChild(canvas);
  const content = el("div", "turn-content");
  wrap.appendChild(petBox);
  wrap.appendChild(content);

  let pet = null;
  if (typeof SproutManager !== "undefined" && SproutManager?.register) {
    pet = SproutManager.register(canvas, isDynamic);
    wrap._sproutPet = pet;
  }

  return { el: wrap, content, petBox, canvas, pet };
}

export function updateLatestPet() {
  if (typeof SproutManager === "undefined" || !SproutManager?.makeAllStatic) return;
  const turnEls = document.querySelectorAll(".assistant-turn");
  SproutManager.makeAllStatic();
  if (turnEls.length > 0) {
    const last = turnEls[turnEls.length - 1];
    if (last._sproutPet) {
      SproutManager.setActive(last._sproutPet);
    }
  }
}

export function ensureTurn() {
  if (state.currentTurn) return state.currentTurn;
  const welcome = $("welcome");
  if (welcome) welcome.remove();
  const turn = createAssistantTurnNode(true);
  const msgBox = $("messages");
  if (msgBox) msgBox.appendChild(turn.el);
  state.currentTurn = turn;
  updateLatestPet();
  return turn;
}

export function discardEmptyTurn() {
  if (!state.currentTurn) return;
  const children = state.currentTurn.content.children;
  if (children.length === 0 || (children.length === 1 && children[0].id === "pending-indicator")) {
    hidePending();
    if (typeof SproutManager !== "undefined" && state.currentTurn.pet && SproutManager?.unregister) {
      SproutManager.unregister(state.currentTurn.pet);
    }
    state.currentTurn.el.remove();
    state.currentTurn = null;
    updateLatestPet();
  }
}

/* ================= 流程重置与渐进展开 ================= */

export function resetFlow() {
  foldTurnGroups(true);
  state.assistant = null;
  state.work = null;
  state.thinking = null;
  state.active = null;
  state.currentTurn = null;
}

export function foldTurnGroups(instant = false) {
  for (const group of state.turnGroups) {
    refreshWorkSummary(group);
    // 回合折叠动画覆盖整段过程时，分组自身收起必须瞬时完成，避免嵌套动画双重重绘
    if (instant) group.el.classList.add("no-anim");
    group.el.classList.remove("live", "expanded");
    if (instant) {
      void group.el.offsetHeight;
      group.el.classList.remove("no-anim");
    }
  }
  state.turnGroups = [];
}

export function setActive(node) {
  const prev = state.active;
  if (prev && prev !== node) prev.classList.remove("expanded");
  state.active = node;
  if (node) node.classList.add("expanded");
}

export function closeWork() {
  if (state.work) refreshWorkSummary(state.work);
  state.work = null;
  state.thinking = null;
}

export function ensureWork() {
  if (state.work) return state.work;
  const welcome = $("welcome");
  if (welcome) welcome.remove();
  state.assistant = null;
  const group = workGroupNode();
  group.el.classList.add("expanded", "live");
  const turn = ensureTurn();
  turn.content.appendChild(group.el);
  state.work = group;
  state.turnGroups.push(group);
  return group;
}

export function ensureAssistant() {
  if (state.assistant) return state.assistant;
  const welcome = $("welcome");
  if (welcome) welcome.remove();
  closeWork();
  const wrap = answerNode("");
  const turn = ensureTurn();
  turn.content.appendChild(wrap);
  state.assistant = { el: wrap, answer: wrap.querySelector(".answer"), text: "" };
  return state.assistant;
}
