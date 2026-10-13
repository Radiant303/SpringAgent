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

export function subscribeStream(sid) {
  rpc.request("stream/subscribe", { sessionId: sid, lastSeq: state.lastSeq[sid] || null })
    .catch(() => {});
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
  if (sid === state.sessionId) return;
  if (state.busy) {
    addSystem("运行中，不能切换会话");
    return;
  }
  try {
    await rpc.request("session/attach", { workspace: state.workspace, sessionId: sid, model: state.model });
  } catch (e) {
    addSystem("❌ 切换会话失败：" + e.message, true);
    return;
  }
  state.sessionId = sid;
  localStorage.setItem("sh.sessionId", sid);
  clearMessages(true);
  try {
    await loadHistory();
  } catch (e) {
    addSystem("❌ 加载历史失败：" + e.message, true);
  }
  await loadPlan();
  await refreshSessionList();
  subscribeStream(sid);
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
  refreshSessionList();
  $("input")?.focus();
}

export async function openWorkSession(preferredSid = null) {
  const currentWorkId = state.workId;
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
    localStorage.setItem("sh.sessionId", target.sessionId);
    try {
      await rpc.request("session/attach", { workspace: state.workspace, sessionId: target.sessionId, model: state.model });
      if (state.workId !== currentWorkId) return;
      await loadHistory();
    } catch (e) {
      if (state.workId !== currentWorkId) return;
      UI.clearSkeleton("messages");
      addSystem("❌ 加载历史失败：" + e.message, true);
    }
    if (state.workId !== currentWorkId) return;
    await loadPlan();
    await refreshSessionList();
    subscribeStream(target.sessionId);
  } else {
    state.sessionId = null;
    localStorage.removeItem("sh.sessionId");
    clearMessages();
    await refreshSessionList();
    $("input")?.focus();
  }
}

export async function openSession() {
  await openWorkSession(localStorage.getItem("sh.sessionId"));
}
