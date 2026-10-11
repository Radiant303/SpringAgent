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

export async function newSession() {
  if (state.busy) {
    addSystem("运行中，不能新建会话");
    return;
  }
  try {
    const r = await rpc.request("session/new", { workspace: state.workspace, model: state.model, workId: state.workId });
    state.sessionId = r.sessionId;
    localStorage.setItem("sh.sessionId", r.sessionId);
    clearMessages();
    await refreshSessionList();
    subscribeStream(r.sessionId);
  } catch (e) {
    addSystem("❌ 新建会话失败：" + e.message, true);
  }
}

export async function openSession() {
  let sid = localStorage.getItem("sh.sessionId");
  if (sid) {
    try {
      await rpc.request("session/attach", { workspace: state.workspace, sessionId: sid, model: state.model });
    } catch {
      sid = null;
    }
  }
  if (!sid) {
    const r = await rpc.request("session/resume_last", { workspace: state.workspace, model: state.model });
    sid = r.sessionId;
  }
  if (!sid) {
    const r = await rpc.request("session/new", { workspace: state.workspace, model: state.model, workId: state.workId });
    sid = r.sessionId;
  }
  state.sessionId = sid;
  localStorage.setItem("sh.sessionId", sid);
  clearMessages(true);
  await loadHistory();
  await loadPlan();
  await refreshSessionList();
  subscribeStream(sid);
}
