/**
 * 会话列表与切换管理
 */

import { $, el } from "../../utils/dom.js";
import { formatTime } from "../../utils/format.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { state } from "./state.js";
import { addSystem } from "./chatView.js";
import { clearMessages, loadHistory } from "./history.js";
import { loadPlan } from "./stream.js";
import { applyModelSelection, updateInputState } from "./input.js";

let attaching = false;

export async function attachSession(sid) {
  const params = { workspace: state.workspace, sessionId: sid, model: state.model, effort: state.effort };
  const socket = rpc.ws;
  const workId = state.workId;
  const apply = (result) => {
    if (rpc.ws === socket && state.workId === workId && !state.connectionReplaced) applyModelSelection(result);
    return result;
  };
  try {
    return apply(await rpc.request("session/attach", params));
  } catch (err) {
    if (err.code !== -32003) throw err;
    let result = null;
    const accepted = await UI.confirm({
      title: "接管会话",
      message: "此会话正在其他页面或设备中使用。接管后，原连接将断开，正在运行的任务会继续。",
      okText: "接管会话",
      submit: async () => {
        if (rpc.ws !== socket || state.connectionReplaced || state.workId !== workId) {
          throw new Error("连接或项目已更换，请重新选择会话");
        }
        result = await rpc.request("session/attach", { ...params, takeover: true });
      },
    });
    return accepted ? apply(result) : null;
  }
}

export function subscribeStream(sid, refreshStatus = false) {
  rpc.request("stream/subscribe", { sessionId: sid, lastSeq: state.lastSeq[sid] || null })
    .then(async () => {
      if (!refreshStatus) return;
      const seq = state.lastSeq[sid];
      const result = await rpc.request("session/status", { sessionId: sid });
      if (state.sessionId === sid && !state.connectionReplaced && state.lastSeq[sid] === seq) {
        state.busy = !!result.busy;
        applyModelSelection(result);
        updateInputState();
      }
    })
    .catch((e) => { if (!state.connectionReplaced) UI.toast(e.message, { type: "error" }); });
}

export async function refreshSessionList() {
  let sessions;
  try {
    const r = await rpc.request("session/list", { workspace: state.workspace, workId: state.workId });
    sessions = r.sessions || [];
  } catch {
    UI.clearSkeleton("session-list");
    return;
  }
  const list = $("session-list");
  if (!list) return;
  list.textContent = "";
  const active = sessions.find((s) => s.sessionId === state.sessionId);
  const titleEl = $("session-title");
  if (titleEl) titleEl.textContent = active ? (active.title || "(空会话)") : "";

  /* 按最近更新倒序：新会话排在列表顶部 */
  const sorted = [...sessions].sort((a, b) => {
    const ta = Date.parse(a.updatedAt || a.createdAt || "") || 0;
    const tb = Date.parse(b.updatedAt || b.createdAt || "") || 0;
    return tb - ta;
  });
  for (const s of sorted) {
    const item = el("div", "session-item" + (s.sessionId === state.sessionId ? " active" : ""));
    item.appendChild(el("span", "session-title", s.title || "(空会话)"));
    item.appendChild(el("span", "session-time", formatTime(s.updatedAt || s.createdAt)));
    item.title = s.sessionId;
    item.onclick = () => switchSession(s.sessionId);
    list.appendChild(item);
  }
}

export async function switchSession(sid) {
  if (attaching || (sid === state.sessionId && state.sessionReady)) return;
  if (!state.connected || state.connectionReplaced) {
    UI.toast("请先重新连接", { type: "error" });
    return;
  }
  if (state.busy) {
    addSystem("运行中，不能切换会话");
    return;
  }
  attaching = true;
  try {
    const result = await attachSession(sid);
    if (!result || state.connectionReplaced) return;
    state.sessionId = sid;
    state.sessionReady = true;
    state.busy = !!result.busy;
    localStorage.setItem("sh.sessionId", sid);
    clearMessages(true);
    await loadHistory();
    await loadPlan();
    await refreshSessionList();
    subscribeStream(sid, true);
  } catch (e) {
    UI.clearSkeleton("messages");
    addSystem("打开会话失败：" + e.message, true);
  } finally {
    attaching = false;
    updateInputState();
  }
}

export function newSession() {
  if (state.busy) {
    addSystem("运行中，不能新建会话");
    return;
  }
  // 草稿态：不真正建会话，首条消息发出时才创建
  if (!state.sessionId) {
    $("input")?.focus();
    return;
  }
  state.sessionId = null;
  localStorage.removeItem("sh.sessionId");
  clearMessages();
  updateInputState();
  refreshSessionList();
  $("input")?.focus();
}

export async function openWorkSession(preferredSid = null) {
  const currentWorkId = state.workId;
  state.sessionReady = false;
  updateInputState();
  UI.showSkeleton("session-list");
  clearMessages(true);
  let sessions;
  try {
    const r = await rpc.request("session/list", { workspace: state.workspace, workId: state.workId });
    sessions = r.sessions || [];
  } catch (e) {
    if (state.workId !== currentWorkId) return;
    UI.clearSkeleton("session-list");
    UI.clearSkeleton("messages");
    addSystem("❌ 获取会话列表失败：" + e.message, true);
    return;
  }
  if (state.workId !== currentWorkId) return;

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
      if (state.workId !== currentWorkId) return;
      UI.clearSkeleton("messages");
      addSystem("挂载会话失败：" + e.message, true);
      await refreshSessionList();
      return;
    }
    if (state.workId !== currentWorkId || state.connectionReplaced) return;
    if (!result) {
      UI.clearSkeleton("messages");
      addSystem("此会话仍由其他连接控制。点击会话可再次选择接管，或选择其他会话。");
      await refreshSessionList();
      return;
    }
    localStorage.setItem("sh.sessionId", target.sessionId);
    state.sessionReady = true;
    state.busy = !!result.busy;
    updateInputState();
    try {
      await loadHistory();
    } catch (e) {
      UI.clearSkeleton("messages");
      addSystem("加载历史失败：" + e.message, true);
    }
    if (state.workId !== currentWorkId || state.connectionReplaced) return;
    await loadPlan();
    await refreshSessionList();
    subscribeStream(target.sessionId, true);
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
