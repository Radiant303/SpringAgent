/**
 * 聊天附件上传与删除。
 *
 * @author hanbing
 * @since 2026-10-14
 */

import { $ } from "../../utils/dom.js";
import { getToken, goLogin } from "../../api/auth.js";
import { api } from "../../api/client.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { state } from "./state.js";
import { addSystem, editorChipNode, hideFloatPreview } from "./chatView.js";
import { editorEl, insertNodeAtCaret, insertPlainText, markKeepOnRemove,
         skipServerDelete, syncPlaceholder } from "./editor.js";
import { subscribeStream, refreshSessionList } from "./sessions.js";
import { updateInputState, applyModelSelection } from "./input.js";

/* 待发送附件登记：uuid → {name, url}（chip DOM 之外的备份，供删除失败重建） */
const pendingAtts = new Map();

async function ensureSession() {
  if (state.sessionId) return;
  const workId = state.workId;
  const r = await rpc.request("session/new", {
    workspace: state.workspace, model: state.model, effort: state.effort, workId,
  });
  if (state.workId !== workId) throw new Error("项目已切换，请重新上传");
  applyModelSelection(r);
  state.sessionId = r.sessionId;
  state.sessionReady = true;
  localStorage.setItem("sh.sessionId", r.sessionId);
  subscribeStream(r.sessionId);
  refreshSessionList();
}

/**
 * 删除附件：先删服务端文件，成功后摘掉 chip；失败则恢复 chip 供重试。
 * node 为空表示 chip 已被键盘删除（退格等），失败时需要重新插回编辑器。
 */
export async function deleteAttachment(uuid, node = null) {
  try {
    await api(`/api/files/${uuid}`, { method: "DELETE" });
  } catch (e) {
    UI.toast(e.message || "移除失败", { type: "error" });
    const att = pendingAtts.get(uuid);
    if (!node && att && editorEl()) {
      const chip = editorChipNode(att, { onRemove: () => deleteAttachment(att.uuid, chip) });
      insertNodeAtCaret(chip);
    }
    return;
  }
  pendingAtts.delete(uuid);
  if (node) {
    markKeepOnRemove(uuid);
    node.remove();
  }
  syncPlaceholder();
  updateInputState();
}

export async function upload(file) {
  const btn = $("upload-btn");
  if (!state.connected || state.busy || state.modelChanging || state.connectionReplaced
      || btn.dataset.uploading || (state.sessionId && !state.sessionReady)) {
    addSystem("当前状态不能上传文件", true);
    return;
  }
  const workId = state.workId;
  btn.dataset.uploading = "1";
  btn.disabled = true;
  btn.title = "上传中…";
  updateInputState();
  try {
    await ensureSession();
    const sessionId = state.sessionId;
    if (state.workId !== workId) throw new Error("项目已切换，请重新上传");
    const form = new FormData();
    form.append("sessionId", sessionId);
    form.append("file", file);
    const headers = {};
    const token = getToken();
    if (token) headers.Authorization = "Bearer " + token;
    const r = await fetch("/api/files", { method: "POST", headers, body: form });
    if (r.status === 401) {
      goLogin("expired");
      throw new Error("登录已过期，请重新登录");
    }
    const payload = await r.json().catch(() => null);
    if (!r.ok) {
      throw new Error((payload && payload.message) || `上传失败（${r.status}）`);
    }
    const att = {
      uuid: payload.data.uuid,
      url: payload.data.url,
      name: file.name,
      mimeType: payload.data.mimeType,
    };
    if (state.workId !== workId || state.sessionId !== sessionId) {
      await api(`/api/files/${att.uuid}`, { method: "DELETE" });
      throw new Error("会话已切换，请重新上传");
    }
    pendingAtts.set(att.uuid, att);
    const chip = editorChipNode(att, { onRemove: () => deleteAttachment(att.uuid, chip) });
    insertNodeAtCaret(chip);
    updateInputState();
  } catch (e) {
    UI.toast(e.message || "上传失败", { type: "error" });
  } finally {
    delete btn.dataset.uploading;
    btn.title = "上传文件";
    updateInputState();
  }
}

/** 粘贴：剪贴板里有文件就上传（并阻止默认插入），否则按纯文本插入 */
async function onPaste(e) {
  const data = e.clipboardData;
  if (!data) return;
  const files = [...(data.items || [])]
    .filter((item) => item.kind === "file")
    .map((item) => item.getAsFile())
    .filter(Boolean);
  if (files.length) {
    e.preventDefault();
    for (const file of files) {
      await upload(file.name && file.name !== "image.png" ? file : renameClipboardFile(file));
    }
    return;
  }
  e.preventDefault();
  const text = data.getData("text/plain");
  if (text) {
    insertPlainText(text);
    updateInputState();
  }
}

/** 剪贴板截图没有语义化文件名，按时间戳命名 */
function renameClipboardFile(file) {
  const ext = (file.type.split("/")[1] || "png").replace(/[^\w]/g, "") || "png";
  const stamp = new Date().toISOString().slice(0, 19).replace(/[-:T]/g, "");
  return new File([file], `clipboard-${stamp}.${ext}`, { type: file.type });
}

/* 退格/删除键移除 chip 时同步删服务端文件 */
function observeChipRemoval(editor) {
  new MutationObserver((mutations) => {
    const removedChips = new Map();
    for (const m of mutations) {
      for (const removed of m.removedNodes) {
        if (removed.nodeType !== 1) continue;
        const chips = removed.matches("[data-attach-uuid]")
          ? [removed] : removed.querySelectorAll("[data-attach-uuid]");
        for (const chip of chips) {
          if (!editor.contains(chip)) removedChips.set(chip.dataset.attachUuid, chip);
        }
      }
    }
    for (const uuid of removedChips.keys()) {
      hideFloatPreview();
      if (skipServerDelete(uuid)) pendingAtts.delete(uuid);
      else deleteAttachment(uuid);
    }
    if (removedChips.size) {
      syncPlaceholder();
      updateInputState();
    }
  }).observe(editor, { childList: true, subtree: true });
}

export function initUpload() {
  const btn = $("upload-btn");
  const picker = $("upload-input");
  const editor = editorEl();
  if (!btn || !picker || !editor) return;
  btn.addEventListener("click", () => {
    if (!btn.disabled) picker.click();
  });
  picker.addEventListener("change", () => {
    const file = picker.files && picker.files[0];
    picker.value = "";
    if (file) upload(file);
  });
  editor.addEventListener("paste", onPaste);
  observeChipRemoval(editor);
}
