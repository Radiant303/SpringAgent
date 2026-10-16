/**
 * 会话列表与切换管理
 */

import { $, el } from "../../utils/dom.js";
import { formatTime } from "../../utils/format.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { state, runs, runOf, isBusy, applyServerBusy } from "./state.js";
import { drainPendingFor } from "./modals.js";
import { addSystem, userNode } from "./chatView.js";
import { clearMessages, loadHistory } from "./history.js";
import { loadPlan } from "./stream.js";
import { applyModelSelection, updateInputState } from "./input.js";

let attaching = false;
let listGeneration = 0;
const sessionTitles = new Map();

export function sessionLabel(sid) {
  return sessionTitles.get(sid) || sid.slice(0, 8);
}

/* 回合运行期间用户消息尚未落库：attach 返回的 pendingInput 先补画气泡，重放事件接在其后 */
function appendPendingInput(result) {
  if (!result?.pendingInput) return;
  const box = $("messages");
  if (box) box.appendChild(userNode(result.pendingInput));
}

export async function attachSession(sid) {
  const params = { workspace: state.workspace, sessionId: sid, model: state.model, effort: state.effort };
  const socket = rpc.ws;
  const workId = state.workId;
  const generation = state.viewGeneration;
  const apply = (result) => {
    if (rpc.ws !== socket || !state.connected || state.workId !== workId || generation !== state.viewGeneration) return null;
    applyModelSelection(result);
    return result;
  };
  try {
    return apply(await rpc.request("session/attach", params));
  } catch (err) {
    if (err.code !== -32003) throw err;
    let result = null;
    const accepted = await UI.confirm({
      title: "接管会话",
      message: "此会话正在其他页面或设备中使用。接管后，原页面将失去此会话的控制权，正在运行的任务和其他会话不受影响。",
      okText: "接管会话",
      submit: async () => {
        if (rpc.ws !== socket || !state.connected || state.workId !== workId || generation !== state.viewGeneration) {
          throw new Error("连接或项目已更换，请重新选择会话");
        }
        result = await rpc.request("session/attach", { ...params, takeover: true });
      },
    });
    return accepted ? apply(result) : null;
  }
}

export function subscribeStream(sid, refreshStatus = false) {
  const socket = rpc.ws;
  rpc.request("stream/subscribe", { sessionId: sid, lastSeq: state.lastSeq[sid] || null })
    .then(async () => {
      if (!refreshStatus || rpc.ws !== socket || !state.connected) return;
      const seq = state.lastSeq[sid];
      const result = await rpc.request("session/status", { sessionId: sid });
      if (rpc.ws !== socket || !state.connected || state.lastSeq[sid] !== seq) return;
      const wasBusy = isBusy(sid);
      applyServerBusy(sid, !!result.busy);
      if (state.sessionId === sid && state.sessionReady) {
        applyModelSelection(result);
        updateInputState();
        // 切换窗口内结束的回合不在历史快照也不在重放范围：已空闲则重载历史补齐
        if (wasBusy && !result.busy && !isBusy(sid)) {
          clearMessages(true);
          await loadHistory(undefined, state.viewGeneration);
          await loadPlan();
        }
      }
      refreshSessionList();
    })
    .catch((e) => { if (rpc.ws === socket && state.connected) UI.toast(e.message, { type: "error" }); });
}

export async function refreshSessionList() {
  const generation = ++listGeneration;
  const workId = state.workId;
  const socket = rpc.ws;
  const before = new Map(Object.entries(runs).map(([sid, run]) => [sid, { run, busy: run.busy, seq: state.lastSeq[sid] }]));
  let sessions;
  try {
    const r = await rpc.request("session/list", { workspace: state.workspace, workId });
    sessions = r.sessions || [];
  } catch {
    if (generation === listGeneration && state.workId === workId) UI.clearSkeleton("session-list");
    return;
  }
  if (generation !== listGeneration || state.workId !== workId || rpc.ws !== socket || !state.connected) return;
  for (const s of sessions) {
    sessionTitles.set(s.sessionId, s.title || "(空会话)");
    const previous = before.get(s.sessionId);
    if (typeof s.busy === "boolean" && (previous
      ? runs[s.sessionId] === previous.run && isBusy(s.sessionId) === previous.busy && state.lastSeq[s.sessionId] === previous.seq
      : !runs[s.sessionId])) applyServerBusy(s.sessionId, s.busy);
  }
  updateInputState();
  const list = $("session-list");
  if (!list) return;
  list.textContent = "";
  const active = sessions.find((s) => s.sessionId === state.sessionId);
  const titleEl = $("session-title");
  if (titleEl) titleEl.textContent = active ? (active.title || "(空会话)") : "";

  const sorted = [...sessions].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt || "") || 0;
    const tb = Date.parse(b.updatedAt || b.createdAt || "") || 0;
    return tb - ta;
  });
  for (const s of sorted) {
    const item = el("div", "session-item" + (s.sessionId === state.sessionId ? " active" : ""));
    const title = el("span", "session-title", s.title || "(空会话)");
    title.title = title.textContent;
    item.appendChild(title);
    const run = runOf(s.sessionId);
    const status = el("span", "session-status");
    const badge = (className, label, text = "") => {
      const node = el("span", className, text);
      node.title = label;
      node.setAttribute("aria-label", label);
      status.appendChild(node);
    };
    if (run.busy) badge("session-running", "运行中");
    if (run.attention) {
      const label = run.attention === "approval" ? "待审批" : "待回答";
      badge("session-attention", label, label);
    }
    if (run.unread) badge("session-unread", "已完成，尚未查看");
    if (status.children.length) item.appendChild(status);
    item.appendChild(el("span", "session-time", formatTime(s.updatedAt || s.createdAt)));
    item.title = s.sessionId;
    item.onclick = () => switchSession(s.sessionId);
    list.appendChild(item);
  }
}

export async function switchSession(sid) {
  if (attaching || (sid === state.sessionId && state.sessionReady)) return;
  if (!state.connected) {
    UI.toast("请先重新连接", { type: "error" });
    return;
  }
  const generation = ++state.viewGeneration;
  attaching = true;
  try {
    const result = await attachSession(sid);
    if (!result || generation !== state.viewGeneration) return;
    state.sessionId = sid;
    state.sessionReady = true;
    applyServerBusy(sid, !!result.busy);
    runOf(sid).unread = false;
    drainPendingFor(null);
    localStorage.setItem("sh.sessionId", sid);
    // 清空续读游标：重新订阅按回合边界重放，补齐离开期间未渲染的事件
    try { await rpc.request("stream/unsubscribe", { sessionId: sid }); } catch { /* 退订失败不阻断切换 */ }
    if (generation !== state.viewGeneration || !state.connected) return;
    state.lastSeq[sid] = null;
    clearMessages(true);
    try {
      await loadHistory(undefined, generation);
    } catch (e) {
      if (generation !== state.viewGeneration) return;
      UI.clearSkeleton("messages");
      addSystem("加载历史失败：" + e.message, true);
    }
    if (generation !== state.viewGeneration || !state.connected || !state.sessionReady) return;
    appendPendingInput(result);
    await loadPlan();
    if (generation !== state.viewGeneration || !state.connected || !state.sessionReady) return;
    drainPendingFor(sid);
    await refreshSessionList();
    if (generation === state.viewGeneration && state.connected && state.sessionReady) subscribeStream(sid, true);
  } catch (e) {
    if (generation !== state.viewGeneration) return;
    UI.clearSkeleton("messages");
    addSystem("打开会话失败：" + e.message, true);
    if (state.sessionId === sid && state.sessionReady) drainPendingFor(sid);
  } finally {
    attaching = false;
    updateInputState();
  }
}

export function newSession() {
  state.viewGeneration++;
  if (!state.sessionId) {
    $("input")?.focus();
    return;
  }
  state.sessionId = null;
  state.sessionReady = false;
  drainPendingFor(null);
  localStorage.removeItem("sh.sessionId");
  clearMessages();
  updateInputState();
  refreshSessionList();
  $("input")?.focus();
}

export async function openWorkSession(preferredSid = null) {
  const generation = ++state.viewGeneration;
  const currentWorkId = state.workId;
  const socket = rpc.ws;
  const isCurrent = () => generation === state.viewGeneration && state.workId === currentWorkId && rpc.ws === socket && state.connected;
  state.sessionReady = false;
  drainPendingFor(null);
  updateInputState();
  UI.showSkeleton("session-list");
  clearMessages(true);
  let sessions;
  try {
    const r = await rpc.request("session/list", { workspace: state.workspace, workId: currentWorkId });
    sessions = r.sessions || [];
  } catch (e) {
    if (!isCurrent()) return;
    UI.clearSkeleton("session-list");
    UI.clearSkeleton("messages");
    addSystem("❌ 获取会话列表失败：" + e.message, true);
    return;
  }
  if (!isCurrent()) return;
  for (const s of sessions) sessionTitles.set(s.sessionId, s.title || "(空会话)");

  const sorted = [...sessions].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt || "") || 0;
    const tb = Date.parse(b.updatedAt || b.createdAt || "") || 0;
    return tb - ta;
  });

  const target = (preferredSid && sorted.find((s) => s.sessionId === preferredSid)) || sorted[0] || null;
  if (target) {
    state.sessionId = target.sessionId;
    let result;
    try {
      result = await attachSession(target.sessionId);
    } catch (e) {
      if (!isCurrent()) return;
      UI.clearSkeleton("messages");
      addSystem("挂载会话失败：" + e.message, true);
      await refreshSessionList();
      return;
    }
    if (!isCurrent()) return;
    if (!result) {
      UI.clearSkeleton("messages");
      addSystem("此会话仍由其他连接控制。点击会话可再次选择接管，或选择其他会话。");
      await refreshSessionList();
      return;
    }
    localStorage.setItem("sh.sessionId", target.sessionId);
    state.sessionReady = true;
    applyServerBusy(target.sessionId, !!result.busy);
    runOf(target.sessionId).unread = false;
    updateInputState();
    try { await rpc.request("stream/unsubscribe", { sessionId: target.sessionId }); } catch { /* 退订失败不阻断挂载 */ }
    if (!isCurrent()) return;
    state.lastSeq[target.sessionId] = null;
    try {
      await loadHistory(undefined, generation);
    } catch (e) {
      if (!isCurrent()) return;
      UI.clearSkeleton("messages");
      addSystem("加载历史失败：" + e.message, true);
    }
    if (!isCurrent() || !state.sessionReady) return;
    appendPendingInput(result);
    await loadPlan();
    if (!isCurrent() || !state.sessionReady) return;
    drainPendingFor(target.sessionId);
    await refreshSessionList();
    if (isCurrent() && state.sessionReady) subscribeStream(target.sessionId, true);
  } else {
    state.sessionId = null;
    localStorage.removeItem("sh.sessionId");
    clearMessages();
    await refreshSessionList();
    updateInputState();
    $("input")?.focus();
  }
}

export async function openSession() {
  await openWorkSession(localStorage.getItem("sh.sessionId"));
}
