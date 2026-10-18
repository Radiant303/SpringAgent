/**
 * 输入框交互、模型切换与布局联动
 */

import { $, el, icon, scrollToBottom, showPending } from "../../utils/dom.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { state, isBusy, setBusy, holdBusy, releaseBusy, resetFlow, setStickToBottom, maybeScroll, ensureTurn, discardEmptyTurn } from "./state.js";
import { userNode, addSystem } from "./chatView.js";
import { subscribeStream, refreshSessionList } from "./sessions.js";
import { drainPendingFor } from "./modals.js";
import { clearEditor, editorHasContent, editorText, keepAttachmentsOnClear } from "./editor.js";

let draftSending = false;

export function updateInputState() {
  const send = $("send-btn");
  const stop = $("stop-btn");
  if (!send || !stop || !$("input")) return;
  const busy = isBusy(state.sessionId);
  const creating = !state.sessionId && draftSending;
  send.classList.toggle("hidden", busy);
  stop.classList.toggle("hidden", !busy);
  const controlled = !state.sessionId || state.sessionReady;
  send.disabled = !state.connected || !controlled || creating || state.modelChanging
    || !!$("upload-btn")?.dataset.uploading || !editorHasContent();
  stop.disabled = !state.connected || !controlled;
  for (const id of ["model-btn", "effort-btn"]) {
    if ($(id)) $(id).disabled = !state.connected || !controlled || busy || creating || state.modelChanging;
  }
  const uploadBtn = $("upload-btn");
  if (uploadBtn && !uploadBtn.dataset.uploading) {
    uploadBtn.disabled = !state.connected || !controlled || busy || creating || state.modelChanging;
  }
  if (!state.connected || !controlled || busy || creating || state.modelChanging) closeModelMenu();
}

export async function send() {
  const input = $("input");
  if (!input) return;
  const fullText = editorText().trim();
  if (!fullText) return;
  if (!state.connected) {
    addSystem("尚未连接，不能发送消息", true);
    return;
  }
  if (state.sessionId && !state.sessionReady) {
    addSystem("当前页面未控制此会话，请重新连接或接管会话", true);
    return;
  }
  if (isBusy(state.sessionId) || (!state.sessionId && draftSending) || state.modelChanging || $("upload-btn")?.dataset.uploading) {
    addSystem("运行、上传或切换模型中，不能发送消息");
    return;
  }
  let sessionId = state.sessionId;
  const workId = state.workId;
  const socket = rpc.ws;
  const isDraft = !sessionId;
  if (isDraft) draftSending = true;
  setBusy(sessionId, true);
  holdBusy(sessionId, 8000);
  updateInputState();
  const welcome = $("welcome");
  if (welcome) welcome.remove();
  resetFlow();
  const box = $("messages");
  if (box) box.appendChild(userNode(fullText));
  const turn = ensureTurn();
  showPending(turn.content);
  setStickToBottom(true);
  scrollToBottom();
  keepAttachmentsOnClear();
  clearEditor();
  try {
    if (isDraft) {
      const r = await rpc.request("session/new", { workspace: state.workspace, model: state.model, effort: state.effort, workId });
      sessionId = r.sessionId;
      if (rpc.ws !== socket || !state.connected) return;
      setBusy(sessionId, true);
      holdBusy(sessionId, 8000);
      if (!state.sessionId && state.workId === workId) {
        applyModelSelection(r);
        state.sessionId = sessionId;
        state.sessionReady = true;
        localStorage.setItem("sa.sessionId", sessionId);
        drainPendingFor(sessionId);
      }
      subscribeStream(sessionId);
    }
    await rpc.request("turn/start", { sessionId, input: fullText });
    refreshSessionList();
  } catch (e) {
    if (rpc.ws === socket && state.connected) releaseBusy(sessionId);
    if (state.sessionId === sessionId || (isDraft && !state.sessionId && state.workId === workId)) {
      discardEmptyTurn();
      addSystem("❌ " + e.message, true);
    } else UI.toast("发送失败：" + e.message, { type: "error" });
    refreshSessionList();
  } finally {
    if (isDraft) draftSending = false;
    updateInputState();
  }
}

export async function cancelTurn() {
  if (!state.connected || !state.sessionReady || !state.sessionId || !isBusy(state.sessionId)) return;
  try {
    await rpc.request("turn/cancel", { sessionId: state.sessionId });
  } catch {
    /* 断线等情况由重连逻辑兜底 */
  }
}

function effortLabel(effort) {
  return effort ? effort[0].toUpperCase() + effort.slice(1) : "";
}

function selectedEffort(model, effort) {
  if (!model?.thinkingEnabled) return null;
  return model.supportEfforts.includes(effort) ? effort : model.defaultEffort;
}

export function applyModelSelection(result) {
  if (!result?.model) return;
  state.model = result.model;
  state.effort = result.effort ?? null;
  populateModelSelect();
}

function selectionItem(name, selected, option, choose) {
  const item = el("button", "model-item");
  item.type = "button";
  item.title = name;
  item.dataset.option = option;
  item.setAttribute("role", "menuitemradio");
  item.setAttribute("aria-checked", String(selected));
  item.disabled = isBusy(state.sessionId) || state.modelChanging;
  const check = el("span", "model-check");
  if (selected) check.appendChild(icon("tick"));
  item.append(check, el("span", "model-item-name", name));
  item.onclick = () => {
    closeModelMenu(true);
    choose();
  };
  return item;
}

function modelMenuItems(menu) {
  return [...(menu?.querySelectorAll("button:not(:disabled)") || [])]
    .filter((item) => item.getClientRects().length);
}

export function positionModelMenus() {
  const menu = $("model-menu");
  if (!menu || menu.classList.contains("hidden")) return;
  const width = document.documentElement.clientWidth;
  const height = window.innerHeight;
  const anchor = $("model-btn").getBoundingClientRect();
  menu.style.left = Math.max(8, Math.min(anchor.right - menu.offsetWidth, width - menu.offsetWidth - 8)) + "px";
  menu.style.top = Math.max(8, anchor.top - menu.offsetHeight - 8) + "px";
  const child = $("effort-menu");
  if (!child || child.classList.contains("hidden")) return;
  const rowTop = menu.offsetTop + $("effort-btn").offsetTop;
  const right = menu.offsetLeft + menu.offsetWidth + 8;
  const left = menu.offsetLeft - child.offsetWidth - 8;
  const side = right + child.offsetWidth <= width - 8 ? right : left >= 8 ? left : null;
  child.style.left = (side ?? Math.max(8, Math.min(menu.offsetLeft, width - child.offsetWidth - 8))) + "px";
  child.style.top = Math.max(8, Math.min(side === null ? rowTop - child.offsetHeight - 8 : rowTop,
    height - child.offsetHeight - 8)) + "px";
}

let modelMenuSnapshot;

export function populateModelSelect() {
  const list = $("model-list");
  const effortMenu = $("effort-menu");
  if (!list || !effortMenu) return;
  const snapshot = JSON.stringify([state.models, state.model, state.effort, isBusy(state.sessionId), state.modelChanging]);
  if (snapshot === modelMenuSnapshot) return;
  const activeMenu = effortMenu.contains(document.activeElement) ? effortMenu : $("model-menu");
  const focused = activeMenu?.contains(document.activeElement) ? document.activeElement : null;
  const option = focused?.dataset.option;
  list.textContent = "";
  for (const m of state.models) {
    list.appendChild(selectionItem(m.displayName || m.id, m.id === state.model, `model:${m.id}`, () => onModelChange(m.id)));
  }
  const cur = state.models.find((m) => m.id === state.model);
  const name = cur ? (cur.displayName || cur.id) : "默认模型";
  const effort = selectedEffort(cur, state.effort);
  const label = effortLabel(effort);
  if ($("model-name")) $("model-name").textContent = name;
  if ($("model-btn")) $("model-btn").title = label ? `${name} · ${label}` : name;
  $("model-effort")?.classList.toggle("hidden", !cur?.thinkingEnabled);
  $("effort-section")?.classList.toggle("hidden", !cur?.thinkingEnabled);
  if ($("effort-name")) $("effort-name").textContent = label;
  if ($("effort-value")) $("effort-value").textContent = label;
  effortMenu.textContent = "";
  for (const level of cur?.thinkingEnabled ? cur.supportEfforts : []) {
    effortMenu.appendChild(selectionItem(effortLabel(level), level === effort, `effort:${level}`, () => onEffortChange(level)));
  }
  if (!cur?.thinkingEnabled) closeEffortMenu();
  positionModelMenus();
  if (focused && !$("model-menu").classList.contains("hidden")) {
    const items = modelMenuItems(activeMenu).length ? modelMenuItems(activeMenu) : modelMenuItems($("model-menu"));
    const target = option ? items.find((item) => item.dataset.option === option) : focused;
    (items.includes(target) ? target : items.find((item) => item.getAttribute("aria-checked") === "true") || items[0])?.focus({ preventScroll: true });
  }
  modelMenuSnapshot = snapshot;
}

function focusSelection(menu) {
  const items = modelMenuItems(menu);
  (items.find((item) => item.getAttribute("aria-checked") === "true") || items[0])?.focus({ preventScroll: true });
}

export function openModelMenu() {
  if ($("model-btn")?.disabled) return;
  closeEffortMenu();
  $("model-menu")?.classList.remove("hidden");
  $("model-pill")?.classList.add("open");
  $("model-btn")?.setAttribute("aria-expanded", "true");
  positionModelMenus();
  focusSelection($("model-menu"));
}

export function openEffortMenu(focus = true) {
  if ($("model-btn")?.disabled || $("model-menu")?.classList.contains("hidden")
      || !state.models.find((m) => m.id === state.model)?.thinkingEnabled) return;
  $("effort-menu")?.classList.remove("hidden");
  $("effort-btn")?.setAttribute("aria-expanded", "true");
  positionModelMenus();
  if (focus) focusSelection($("effort-menu"));
}

export function closeEffortMenu(restoreFocus = false) {
  $("effort-menu")?.classList.add("hidden");
  $("effort-btn")?.setAttribute("aria-expanded", "false");
  if (restoreFocus) $("effort-btn")?.focus();
}

export function closeModelMenu(restoreFocus = false) {
  closeEffortMenu();
  $("model-menu")?.classList.add("hidden");
  $("model-pill")?.classList.remove("open");
  $("model-btn")?.setAttribute("aria-expanded", "false");
  if (restoreFocus) $("model-btn")?.focus();
}

export function modelMenuKeydown(event) {
  const { key } = event;
  const open = !$("model-menu")?.classList.contains("hidden");
  if (!open) {
    if (event.target === $("model-btn") && (key === "ArrowDown" || key === "ArrowUp")) {
      event.preventDefault();
      openModelMenu();
      refreshModelList();
    }
    return;
  }
  if (key === "Tab") {
    closeModelMenu();
    $("model-btn")?.focus();
    return;
  }
  const childOpen = !$("effort-menu")?.classList.contains("hidden");
  if (key === "Escape" || (key === "ArrowLeft" && childOpen)) {
    event.preventDefault();
    event.stopPropagation();
    if (childOpen) closeEffortMenu(true);
    else closeModelMenu(true);
    return;
  }
  if (key === "ArrowRight" && event.target === $("effort-btn")) {
    event.preventDefault();
    openEffortMenu();
    return;
  }
  const childFocused = $("effort-menu")?.contains(document.activeElement);
  const items = modelMenuItems($(childFocused ? "effort-menu" : "model-menu"));
  const index = items.indexOf(document.activeElement);
  const next = key === "Home" ? 0 : key === "End" ? items.length - 1
    : key === "ArrowDown" ? (index + 1) % items.length
    : key === "ArrowUp" ? (index - 1 + items.length) % items.length : null;
  if (next !== null) {
    event.preventDefault();
    if (!childFocused) closeEffortMenu();
    items[next]?.focus();
  }
}

let modelListRequest;
export function refreshModelList() {
  if (modelListRequest) return modelListRequest;
  modelListRequest = (async () => {
    try {
      const res = await rpc.request("models/list", {});
      if (!Array.isArray(res?.models)) return;
      state.models = res.models;
      const current = state.models.find((m) => m.id === state.model);
      const model = current || state.models.find((m) => m.id === res.defaultModel);
      const effort = selectedEffort(model, current ? state.effort : null);
      if (!isBusy(state.sessionId) && !state.modelChanging && model && (state.model !== model.id || state.effort !== effort)) {
        await changeSelection(model.id, effort);
      }
      populateModelSelect();
    } catch {
      /* 连接恢复后重新获取 */
    } finally {
      modelListRequest = null;
    }
  })();
  return modelListRequest;
}

async function changeSelection(model, effort) {
  if (isBusy(state.sessionId) || state.modelChanging || !state.connected
      || (state.sessionId && !state.sessionReady)) return false;
  state.modelChanging = true;
  updateInputState();
  const sessionId = state.sessionId;
  const socket = rpc.ws;
  try {
    const result = sessionId
      ? await rpc.request("session/set_model", { sessionId, model, effort })
      : { model, effort };
    if (state.sessionId !== sessionId || rpc.ws !== socket) return false;
    applyModelSelection(result);
    return true;
  } catch (e) {
    addSystem("切换模型或思考强度失败：" + e.message, true);
    return false;
  } finally {
    state.modelChanging = false;
    populateModelSelect();
    updateInputState();
  }
}

export async function onModelChange(picked) {
  if (!picked || picked === state.model) return;
  const model = state.models.find((m) => m.id === picked);
  if (model) await changeSelection(picked, selectedEffort(model, null));
}

export async function onEffortChange(effort) {
  const model = state.models.find((m) => m.id === state.model);
  if (!model?.thinkingEnabled || !model.supportEfforts.includes(effort) || effort === state.effort) return;
  await changeSelection(model.id, effort);
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
