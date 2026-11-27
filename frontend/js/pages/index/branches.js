/**
 * 消息编辑、回答重试与分支切换
 */

import { $, el, icon, showPending } from "../../utils/dom.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";

export function branchInfo(message) {
  const value = message?.metadata?.springAgent;
  return value && typeof value.branchId === "string" ? value : null;
}

function toolButton(name, iconName, label, disabled = false) {
  const button = el("button", "message-tool");
  button.type = "button";
  button.dataset.action = name;
  button.title = label;
  button.setAttribute("aria-label", label);
  button.disabled = disabled;
  button.appendChild(icon(iconName));
  return button;
}

function versionPager(info, disabled) {
  const pager = el("div", "branch-pager");
  const previous = toolButton("previous", "prev", "上一版本", disabled || !info.previousBranchId);
  const next = toolButton("next", "next", "下一版本", disabled || !info.nextBranchId);
  const label = el("span", "branch-version", `${info.version} / ${info.versionCount}`);
  label.setAttribute("aria-label", `第 ${info.version} 个版本，共 ${info.versionCount} 个版本`);
  pager.append(previous, label, next);
  return pager;
}

export function branchToolbar(info, options) {
  if (!info) return null;
  const { kind, text = "", editLimit = 6, retryLimit = 6, busy = false } = options;
  const view = kind === "assistant" ? {
    ...info,
    version: info.answerVersion ?? 1,
    versionCount: info.answerVersionCount ?? 1,
    previousBranchId: info.previousAnswerBranchId,
    nextBranchId: info.nextAnswerBranchId,
  } : info;
  const tools = el("div", `message-tools ${kind}`);
  const copy = toolButton("copy", "copy", "复制", false);
  copy.onclick = (event) => {
    event.stopPropagation();
    navigator.clipboard.writeText(text).catch(() => UI.toast("复制失败", { type: "error" }));
  };
  tools.appendChild(copy);
  if (kind === "user" && editLimit > 0) {
    const exhausted = Number(info.editCount || 0) >= editLimit;
    const edit = toolButton("edit", "edit", exhausted ? `修改次数已达上限（${editLimit} 次）` : "修改", busy || exhausted);
    edit.onclick = (event) => {
      event.stopPropagation();
      startUserEdit(tools.closest(".user-entry"), info, text);
    };
    tools.appendChild(edit);
  }
  if (kind === "assistant" && retryLimit > 0) {
    const exhausted = Number(info.retryCount || 0) >= retryLimit;
    const retry = toolButton("retry", "retry", exhausted ? `重新生成次数已达上限（${retryLimit} 次）` : "重新生成", busy || exhausted);
    retry.onclick = (event) => {
      event.stopPropagation();
      runBranchAction("retry", info.branchId, "", tools.closest(".assistant-turn"));
    };
    tools.appendChild(retry);
  }
  if (Number(view.versionCount || 1) > 1) {
    const pager = versionPager(view, busy);
    pager.addEventListener("click", (event) => {
      const button = event.target.closest("button[data-action]");
      if (!button || button.disabled) return;
      event.stopPropagation();
      const branchId = button.dataset.action === "previous" ? view.previousBranchId : view.nextBranchId;
      if (branchId) selectBranch(branchId);
    });
    if (kind === "assistant") tools.prepend(pager);
    else tools.appendChild(pager);
  }
  return tools;
}

function startUserEdit(entry, info, text) {
  if (!entry || entry.classList.contains("editing")) return;
  const bubble = entry.querySelector(".msg.user");
  const tools = entry.querySelector(".message-tools");
  const form = el("div", "user-edit-form");
  const textarea = el("textarea", "user-edit-input");
  textarea.value = text;
  textarea.maxLength = 64000;
  textarea.rows = 3;
  textarea.setAttribute("aria-label", "修改消息");
  const actions = el("div", "user-edit-actions");
  const cancel = el("button", "user-edit-cancel", "取消");
  const save = el("button", "user-edit-save", "发送");
  cancel.type = "button";
  save.type = "button";
  save.disabled = !textarea.value.trim();
  actions.append(cancel, save);
  form.append(textarea, actions);
  entry.classList.add("editing");
  bubble?.classList.add("hidden");
  if (tools) tools.classList.add("hidden");
  entry.appendChild(form);
  const resize = () => {
    textarea.style.height = "auto";
    textarea.style.height = Math.min(textarea.scrollHeight, 320) + "px";
  };
  textarea.addEventListener("input", () => {
    save.disabled = !textarea.value.trim();
    resize();
  });
  textarea.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      cancel.click();
    } else if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.isComposing) {
      event.preventDefault();
      save.click();
    }
  });
  cancel.onclick = () => {
    form.remove();
    entry.classList.remove("editing");
    bubble?.classList.remove("hidden");
    tools?.classList.remove("hidden");
  };
  save.onclick = () => {
    const value = textarea.value.trim();
    if (!value || save.disabled) return;
    save.disabled = true;
    cancel.disabled = true;
    runBranchAction("edit", info.branchId, value, entry);
  };
  resize();
  textarea.focus();
  textarea.setSelectionRange(textarea.value.length, textarea.value.length);
}

async function reloadHistory() {
  const [{ state }, history, input] = await Promise.all([
    import("./state.js"), import("./history.js"), import("./input.js"),
  ]);
  const sessionId = state.sessionId;
  const generation = state.viewGeneration;
  history.clearMessages();
  try {
    await history.loadHistory(undefined, generation);
  } finally {
    if (state.sessionId === sessionId && state.viewGeneration === generation) input.updateInputState();
  }
}

async function runBranchAction(action, branchId, text, anchor) {
  const [{ state, isBusy, setBusy, holdBusy, releaseBusy, resetFlow, ensureTurn }, input, chat] = await Promise.all([
    import("./state.js"), import("./input.js"), import("./chatView.js"),
  ]);
  if (!anchor?.isConnected || !state.connected || !state.sessionReady || isBusy(state.sessionId)) return;
  const sessionId = state.sessionId;
  const socket = rpc.ws;
  setBusy(sessionId, true);
  holdBusy(sessionId, 8000);
  input.updateInputState();
  resetFlow();
  if (action === "edit" && anchor?.classList.contains("user-entry")) {
    const replacement = chat.userNode(text);
    anchor.replaceWith(replacement);
    while (replacement.nextSibling) replacement.nextSibling.remove();
  } else if (action === "retry" && anchor?.classList.contains("assistant-turn")) {
    while (anchor.nextSibling) anchor.nextSibling.remove();
    anchor.remove();
  }
  showPending(ensureTurn().content);
  try {
    await rpc.request(action === "edit" ? "turn/edit" : "turn/regenerate", {
      sessionId, branchId, input: text,
    });
  } catch (error) {
    if (rpc.ws === socket) releaseBusy(sessionId);
    await reloadHistory().catch(() => {});
    UI.toast(action === "edit" ? "消息修改失败" : "重新生成失败", { type: "error", description: error.message });
    input.updateInputState();
  }
}

async function selectBranch(branchId) {
  const [{ state, isBusy }, history, input] = await Promise.all([
    import("./state.js"), import("./history.js"), import("./input.js"),
  ]);
  if (!state.sessionReady || isBusy(state.sessionId)) return;
  state.activeNodeId = branchId;
  state.branchSelections.set(state.sessionId, branchId);
  history.renderNodePath(branchId);
  input.updateInputState();
}
