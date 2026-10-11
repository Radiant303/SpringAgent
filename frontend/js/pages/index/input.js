/**
 * 输入框交互、模型切换与布局联动
 */

import { $, el, icon, scrollToBottom, showPending, hidePending } from "../../utils/dom.js";
import { rpc } from "../../api/rpc.js";
import { state, resetFlow, setStickToBottom, maybeScroll } from "./state.js";
import { userNode, addSystem } from "./chatView.js";
import { subscribeStream, refreshSessionList } from "./sessions.js";

export function updateInputState() {
  const send = $("send-btn");
  const stop = $("stop-btn");
  const input = $("input");
  if (!send || !stop || !input) return;
  send.classList.toggle("hidden", state.busy);
  stop.classList.toggle("hidden", !state.busy);
  send.disabled = !state.connected || !input.value.trim();
  stop.disabled = !state.connected;
}

export async function send() {
  const input = $("input");
  if (!input) return;
  const text = input.value.trim();
  if (!text) return;
  if (!state.connected) {
    addSystem("尚未连接，不能发送消息", true);
    return;
  }
  if (state.busy) {
    addSystem("运行中，不能发送消息");
    return;
  }
  state.busy = true;
  updateInputState();
  const welcome = $("welcome");
  if (welcome) welcome.remove();
  resetFlow();
  const box = $("messages");
  if (box) box.appendChild(userNode(text));
  showPending();
  setStickToBottom(true);
  scrollToBottom();
  input.value = "";
  input.style.height = "auto";
  try {
    // 草稿态下首条消息：先建会话再开回合
    if (!state.sessionId) {
      const r = await rpc.request("session/new", { workspace: state.workspace, model: state.model, workId: state.workId });
      state.sessionId = r.sessionId;
      localStorage.setItem("sh.sessionId", r.sessionId);
      subscribeStream(r.sessionId);
      refreshSessionList();
    }
    await rpc.request("turn/start", { sessionId: state.sessionId, input: text });
  } catch (e) {
    hidePending();
    state.busy = false;
    updateInputState();
    addSystem("❌ " + e.message, true);
  }
}

export async function cancelTurn() {
  if (!state.busy || !state.sessionId) return;
  try {
    await rpc.request("turn/cancel", { sessionId: state.sessionId });
  } catch {
    /* 断线等情况由重连逻辑兜底 */
  }
}

export function populateModelSelect() {
  const menu = $("model-menu");
  if (!menu) return;
  menu.textContent = "";
  for (const m of state.models) {
    const item = el("div", "model-item");
    const check = el("span", "model-check");
    if (m.id === state.model) check.appendChild(icon("tick"));
    item.appendChild(check);
    item.appendChild(el("span", "model-item-name", m.displayName || m.id));
    item.onclick = () => {
      closeModelMenu();
      onModelChange(m.id);
    };
    menu.appendChild(item);
  }
  const cur = state.models.find((m) => m.id === state.model);
  const nameEl = $("model-name");
  if (nameEl) nameEl.textContent = cur ? (cur.displayName || cur.id) : "默认模型";
}

export function closeModelMenu() {
  const menu = $("model-menu");
  if (menu) menu.classList.add("hidden");
  const pill = $("model-pill");
  if (pill) pill.classList.remove("open");
}

let modelListRefreshing = false;
export async function refreshModelList() {
  if (modelListRefreshing) return;
  modelListRefreshing = true;
  try {
    const res = await rpc.request("models/list", {});
    if (res && Array.isArray(res.models)) {
      state.models = res.models;
      // 选中的模型已被站长删除：回退默认模型
      if (state.model && !res.models.some((m) => m.id === state.model)) {
        state.model = res.defaultModel || null;
      }
      if (!state.model && res.defaultModel) state.model = res.defaultModel;
      populateModelSelect();
    }
  } catch {
    /* 保留旧列表 */
  } finally {
    modelListRefreshing = false;
  }
}

export async function onModelChange(picked) {
  if (!picked || picked === state.model) return;
  state.model = picked;
  populateModelSelect();
  try {
    if (state.sessionId) await rpc.request("session/set_model", { sessionId: state.sessionId, model: picked });
    addSystem(`已切换到 ${$("model-name") ? $("model-name").textContent : picked}`);
  } catch (e) {
    addSystem("❌ 切换模型失败：" + e.message, true);
  }
}

export function syncEmpty() {
  const box = $("messages");
  const main = $("main");
  if (!box || !main) return;
  const hasContent = box.querySelector(".msg-skeleton, .msg:not(.system), .assistant-turn, .work-group, .pending-indicator, .load-more");
  main.classList.toggle("is-empty", !hasContent);
}

export function syncChrome() {
  const dock = $("plan-dock");
  const pill = $("plan-pill");
  const inputArea = $("input-area");
  if (!dock || !pill || !inputArea) return;
  const dockHidden = dock.classList.contains("hidden");
  const pillH = dockHidden ? 0 : pill.offsetHeight;
  document.documentElement.style.setProperty("--plan-h", pillH + "px");
  const inputH = inputArea.offsetHeight;
  const gap = dockHidden ? 12 : pillH + 10;
  document.documentElement.style.setProperty("--scroll-b", inputH + gap + "px");
  maybeScroll(true);
}
