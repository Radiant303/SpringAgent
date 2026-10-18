/**
 * 会话列表与切换管理
 */

import { $, el } from "../../utils/dom.js";
import { formatTime } from "../../utils/format.js";
import { rpc } from "../../api/rpc.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { notifyStorageChanged } from "../../utils/storage.js";
import { state, runs, runOf, isBusy, applyServerBusy } from "./state.js";
import { drainPendingFor, clearPendingFor } from "./modals.js";
import { discardSessionAttachments } from "./upload.js";
import { addSystem, userNode } from "./chatView.js";
import { clearMessages, loadHistory } from "./history.js";
import { loadPlan } from "./stream.js";
import { applyModelSelection, updateInputState } from "./input.js";

let listGeneration = 0;
let loadQueue = Promise.resolve();
let queueSocket = null;
let loadingView = null;
const sessionTitles = new Map();

export function sessionLabel(sid) {
  return sessionTitles.get(sid) || sid.slice(0, 8);
}

function viewContext(sid = state.sessionId) {
  return { sid, generation: state.viewGeneration, workId: state.workId, workspace: state.workspace, socket: rpc.ws };
}

function isConnectionCurrent(view) {
  return rpc.ws === view.socket && state.connected && state.workspace === view.workspace
    && !state.deletedSessions.has(view.sid);
}

function isScopeCurrent(view) {
  return isConnectionCurrent(view) && state.workId === view.workId && state.viewGeneration === view.generation;
}

function isViewCurrent(view) {
  return isScopeCurrent(view) && state.sessionId === view.sid;
}

function syncSessionSelection() {
  for (const item of $("session-list")?.querySelectorAll(".session-item") || []) {
    item.classList.toggle("active", item.dataset.sessionId === state.sessionId);
  }
  const title = $("session-title");
  if (title) title.textContent = state.sessionId ? sessionLabel(state.sessionId) : "";
}

function selectSessionView(sid) {
  state.sessionId = sid;
  state.sessionReady = false;
  syncSessionSelection();
  drainPendingFor(null);
  clearMessages(true);
  updateInputState();
}

function appendPendingInput(result) {
  if (!result?.pendingInput) return;
  const box = $("messages");
  if (box) box.appendChild(userNode(result.pendingInput));
}

export async function attachSession(sid, view = viewContext(sid)) {
  const params = { workspace: view.workspace, sessionId: sid, model: state.model, effort: state.effort };
  const apply = (result) => {
    if (!isViewCurrent(view)) return null;
    applyModelSelection(result);
    return result;
  };
  if (!isViewCurrent(view)) return null;
  try {
    return apply(await rpc.request("session/attach", params));
  } catch (err) {
    if (!isViewCurrent(view)) return null;
    if (err.code !== -32003) throw err;
    let result = null;
    const accepted = await UI.confirm({
      title: "接管会话",
      message: "此会话正在其他页面或设备中使用。接管后，原页面将失去此会话的控制权，正在运行的任务和其他会话不受影响。",
      okText: "接管会话",
      submit: async () => {
        if (!isViewCurrent(view)) throw new Error("连接或会话已更换，请重新选择会话");
        result = await rpc.request("session/attach", { ...params, takeover: true });
      },
    });
    return accepted ? apply(result) : null;
  }
}

export function subscribeStream(sid, refreshStatus = false) {
  if (state.deletedSessions.has(sid)) return Promise.resolve();
  const view = viewContext(sid);
  return rpc.request("stream/subscribe", { sessionId: sid, lastSeq: state.lastSeq[sid] || null })
    .then(async () => {
      if (!refreshStatus || !isConnectionCurrent(view)) return;
      const seq = state.lastSeq[sid];
      const result = await rpc.request("session/status", { sessionId: sid });
      if (!isConnectionCurrent(view) || state.lastSeq[sid] !== seq) return;
      const wasBusy = isBusy(sid);
      applyServerBusy(sid, !!result.busy);
      if (isViewCurrent(view) && state.sessionReady) {
        applyModelSelection(result);
        updateInputState();
        if (wasBusy && !result.busy && !isBusy(sid)) {
          selectSessionView(sid);
          queueSessionLoad(view, result);
        }
      } else if (wasBusy && !isBusy(sid)) {
        runOf(sid).unread = true;
      }
      refreshSessionList();
    })
    .catch((e) => { if (isViewCurrent(view)) UI.toast(e.message, { type: "error" }); });
}

function queueSessionLoad(view, attachedResult) {
  if (queueSocket !== view.socket) {
    queueSocket = view.socket;
    loadQueue = Promise.resolve();
  }
  loadingView = view;
  const task = loadQueue.then(async () => {
    if (!isViewCurrent(view)) return;
    let unsubscribed = false;
    let subscribed = false;
    try {
      const result = attachedResult || await attachSession(view.sid, view);
      if (!isViewCurrent(view)) return;
      if (!result) {
        UI.clearSkeleton("messages");
        addSystem("此会话仍由其他连接控制。点击会话可再次选择接管，或选择其他会话。");
        return;
      }
      applyServerBusy(view.sid, !!result.busy);
      localStorage.setItem("sh.sessionId", view.sid);
      unsubscribed = true;
      try { await rpc.request("stream/unsubscribe", { sessionId: view.sid }); } catch {}
      if (!isViewCurrent(view)) return;
      state.lastSeq[view.sid] = null;
      try {
        await loadHistory(undefined, view.generation);
      } catch (e) {
        if (!isViewCurrent(view)) return;
        UI.clearSkeleton("messages");
        addSystem("加载历史失败：" + e.message, true);
      }
      if (!isViewCurrent(view)) return;
      appendPendingInput(result);
      await loadPlan(view.generation);
      if (!isViewCurrent(view)) return;
      state.sessionReady = true;
      runOf(view.sid).unread = false;
      drainPendingFor(view.sid);
      updateInputState();
      subscribed = true;
      await subscribeStream(view.sid, true);
      if (isViewCurrent(view)) refreshSessionList();
    } catch (e) {
      if (!isViewCurrent(view)) return;
      state.sessionReady = false;
      UI.clearSkeleton("messages");
      addSystem("打开会话失败：" + e.message, true);
    } finally {
      if (unsubscribed && !subscribed && isConnectionCurrent(view)) await subscribeStream(view.sid, true);
      if (loadingView === view) loadingView = null;
      if (isViewCurrent(view)) updateInputState();
    }
  });
  view.task = task;
  loadQueue = task.catch(() => {});
  return task;
}

export async function refreshSessionList() {
  const generation = ++listGeneration;
  const workId = state.workId;
  const socket = rpc.ws;
  const before = new Map(Object.entries(runs).map(([sid, run]) => [sid, { run, busy: run.busy, seq: state.lastSeq[sid] }]));
  let sessions;
  try {
    const r = await rpc.request("session/list", { workspace: state.workspace, workId });
    sessions = (r.sessions || []).filter((session) => !state.deletedSessions.has(session.sessionId));
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
  renderSessionList(sessions);
}

function renderSessionList(sessions) {
  const list = $("session-list");
  if (!list) return;
  list.textContent = "";
  syncSessionSelection();

  const sorted = [...sessions].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt || "") || 0;
    const tb = Date.parse(b.updatedAt || b.createdAt || "") || 0;
    return tb - ta;
  });
  for (const s of sorted) {
    const item = el("div", "session-item" + (s.sessionId === state.sessionId ? " active" : ""));
    item.dataset.sessionId = s.sessionId;
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
    const remove = el("button", "work-del session-del");
    remove.type = "button";
    remove.title = run.busy ? "请结束当前任务后删除" : "删除会话";
    remove.setAttribute("aria-label", "删除会话");
    remove.disabled = run.busy;
    remove.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7 7l10 10M17 7L7 17"/></svg>';
    remove.onclick = (event) => {
      event.stopPropagation();
      deleteSession(s);
    };
    item.appendChild(remove);
    item.title = s.sessionId;
    item.onclick = () => switchSession(s.sessionId);
    list.appendChild(item);
  }
}

export async function deleteSession(session) {
  const sid = session.sessionId;
  const ok = await UI.confirm({
    title: "删除会话",
    message: `确定删除「${session.title || "空会话"}」吗？\n会话消息、附件和专属目录将被清理，计费记录保留。此操作不可恢复。`,
    okText: "删除",
    danger: true,
    submit: () => api(`/api/sessions/${encodeURIComponent(sid)}`, { method: "DELETE" }),
  });
  if (!ok) return;
  onSessionInvalidated({ sessionId: sid, deleted: true });
  UI.toast("会话已删除");
}

export function onSessionInvalidated({ sessionId: sid, deleted }) {
  clearPendingFor(sid);
  delete runs[sid];
  delete state.lastSeq[sid];
  if (!deleted) {
    if (state.sessionId === sid) {
      state.sessionReady = false;
      loadingView = null;
      switchSession(sid);
    }
    return;
  }
  if (state.deletedSessions.has(sid)) return;
  state.deletedSessions.add(sid);
  sessionTitles.delete(sid);
  discardSessionAttachments(sid);
  listGeneration++;
  if (localStorage.getItem("sh.sessionId") === sid) localStorage.removeItem("sh.sessionId");
  if (state.sessionId === sid) newSession();
  else refreshSessionList();
  notifyStorageChanged();
}

export function switchSession(sid) {
  if (state.deletedSessions.has(sid)) return;
  if (sid === state.sessionId && state.sessionReady) return;
  if (!state.connected) {
    UI.toast("请先重新连接", { type: "error" });
    return;
  }
  if (loadingView?.sid === sid && isViewCurrent(loadingView)) return loadingView.task;
  state.viewGeneration++;
  selectSessionView(sid);
  return queueSessionLoad(viewContext(sid));
}

export function newSession() {
  state.viewGeneration++;
  state.sessionId = null;
  state.sessionReady = false;
  syncSessionSelection();
  drainPendingFor(null);
  localStorage.removeItem("sh.sessionId");
  clearMessages();
  updateInputState();
  refreshSessionList();
  $("input")?.focus();
}

export async function openWorkSession(preferredSid = null) {
  state.viewGeneration++;
  listGeneration++;
  const view = viewContext();
  selectSessionView(preferredSid);
  UI.showSkeleton("session-list");
  let sessions;
  try {
    const r = await rpc.request("session/list", { workspace: view.workspace, workId: view.workId });
    sessions = (r.sessions || []).filter((session) => !state.deletedSessions.has(session.sessionId));
  } catch (e) {
    if (!isScopeCurrent(view)) return;
    UI.clearSkeleton("session-list");
    UI.clearSkeleton("messages");
    addSystem("❌ 获取会话列表失败：" + e.message, true);
    return;
  }
  if (!isScopeCurrent(view)) return;
  for (const s of sessions) sessionTitles.set(s.sessionId, s.title || "(空会话)");

  const sorted = [...sessions].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt || "") || 0;
    const tb = Date.parse(b.updatedAt || b.createdAt || "") || 0;
    return tb - ta;
  });

  const target = (preferredSid && sorted.find((s) => s.sessionId === preferredSid)) || sorted[0] || null;
  if (target) {
    view.sid = target.sessionId;
    selectSessionView(view.sid);
    renderSessionList(sessions);
    return queueSessionLoad(view);
  }
  state.sessionId = null;
  localStorage.removeItem("sh.sessionId");
  clearMessages();
  renderSessionList(sessions);
  updateInputState();
  $("input")?.focus();
}

export async function openSession() {
  await openWorkSession(localStorage.getItem("sh.sessionId"));
}
