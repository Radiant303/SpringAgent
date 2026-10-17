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
import { state, isBusy } from "./state.js";
import { addSystem, editorChipNode, hideFloatPreview } from "./chatView.js";
import { editorEl, insertNodeAtCaret, insertPlainText, markKeepOnRemove,
         skipServerDelete, syncPlaceholder } from "./editor.js";
import { subscribeStream, refreshSessionList } from "./sessions.js";
import { updateInputState, applyModelSelection } from "./input.js";
import { notifyStorageChanged } from "../../utils/storage.js";

/* 待发送附件登记：fileId → {name, url}（chip DOM 之外的备份，供删除失败重建） */
const pendingAtts = new Map();

/** 单文件大小上限（字节） */
const MAX_FILE_BYTES = 100 * 1024 * 1024;
/** 输入框内待发送附件数量上限 */
const MAX_ATTACHMENTS = 50;

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
export async function deleteAttachment(fileId, node = null) {
  try {
    await api(`/api/files/${fileId}`, { method: "DELETE" });
  } catch (e) {
    UI.toast(e.message || "移除失败", { type: "error" });
    const att = pendingAtts.get(fileId);
    if (!node && att && editorEl()) {
      const chip = editorChipNode(att, { onRemove: () => deleteAttachment(att.id, chip) });
      insertNodeAtCaret(chip);
    }
    return;
  }
  pendingAtts.delete(fileId);
  notifyStorageChanged();
  if (node) {
    markKeepOnRemove(fileId);
    node.remove();
  }
  syncPlaceholder();
  updateInputState();
}

/** 批量上传附件，逐文件独立成败。 */
export async function uploadFiles(files) {
  const btn = $("upload-btn");
  if (!state.connected || isBusy(state.sessionId) || state.modelChanging
      || btn.dataset.uploading || (state.sessionId && !state.sessionReady)) {
    addSystem("当前状态不能上传文件", true);
    return;
  }
  const free = MAX_ATTACHMENTS - pendingAtts.size;
  if (free <= 0) {
    UI.toast(`附件最多 ${MAX_ATTACHMENTS} 个，请先移除部分附件`, { type: "error" });
    return;
  }
  if (files.length > free) {
    UI.toast(`附件最多 ${MAX_ATTACHMENTS} 个，已忽略超出的 ${files.length - free} 个文件`, { type: "error" });
    files = files.slice(0, free);
  }
  const oversized = files.filter((file) => file.size > MAX_FILE_BYTES);
  if (oversized.length) {
    files = files.filter((file) => file.size <= MAX_FILE_BYTES);
    const names = oversized.slice(0, 3).map((file) => file.name).join("、");
    const suffix = oversized.length > 3 ? ` 等 ${oversized.length} 个文件` : "";
    UI.toast(`${names}${suffix} 超过 100 MB，已跳过`, { type: "error" });
  }
  if (!files.length) return;
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
    for (const file of files) form.append("files", file);
    const headers = {};
    const token = getToken();
    if (token) headers.Authorization = "Bearer " + token;
    const r = await fetch("/api/files/batch", { method: "POST", headers, body: form });
    if (r.status === 401) {
      goLogin("expired");
      throw new Error("登录已过期，请重新登录");
    }
    const payload = await r.json().catch(() => null);
    if (!r.ok) {
      throw new Error((payload && payload.message) || `上传失败（${r.status}）`);
    }
    const uploaded = (payload.data && payload.data.uploaded) || [];
    const failed = (payload.data && payload.data.failed) || [];
    if (uploaded.length) notifyStorageChanged();
    if (state.workId !== workId || state.sessionId !== sessionId) {
      for (const item of uploaded) {
        await api(`/api/files/${item.id}`, { method: "DELETE" }).catch(() => {});
      }
      throw new Error("会话已切换，请重新上传");
    }
    for (const item of uploaded) {
      const att = { id: item.id, url: item.url, name: files[item.index].name, mimeType: item.mimeType };
      pendingAtts.set(att.id, att);
      const chip = editorChipNode(att, { onRemove: () => deleteAttachment(att.id, chip) });
      insertNodeAtCaret(chip);
    }
    if (failed.length) {
      UI.toast(`${failed.length} 个文件上传失败：${failed[0].message}`, { type: "error" });
    }
    updateInputState();
  } catch (e) {
    UI.toast(e.message || "上传失败", { type: "error" });
  } finally {
    delete btn.dataset.uploading;
    btn.title = "上传文件";
    updateInputState();
  }
}

/** 粘贴：剪贴板里有文件就批量上传（并阻止默认插入），否则按纯文本插入 */
async function onPaste(e) {
  const data = e.clipboardData;
  if (!data) return;
  const files = [...(data.items || [])]
    .filter((item) => item.kind === "file")
    .map((item) => item.getAsFile())
    .filter(Boolean);
  if (files.length) {
    e.preventDefault();
    await uploadFiles(files.map((file) =>
      file.name && file.name !== "image.png" ? file : renameClipboardFile(file)));
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
        const chips = removed.matches("[data-attach-id]")
          ? [removed] : removed.querySelectorAll("[data-attach-id]");
        for (const chip of chips) {
          if (!editor.contains(chip)) removedChips.set(chip.dataset.attachId, chip);
        }
      }
    }
    for (const fileId of removedChips.keys()) {
      hideFloatPreview();
      if (skipServerDelete(fileId)) pendingAtts.delete(fileId);
      else deleteAttachment(fileId);
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
    const files = [...(picker.files || [])];
    picker.value = "";
    if (files.length) uploadFiles(files);
  });
  editor.addEventListener("paste", onPaste);
  observeChipRemoval(editor);
}
