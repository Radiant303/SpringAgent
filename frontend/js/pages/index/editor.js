/**
 * 聊天消息内联编辑器。
 *
 * @author hanbing
 * @since 2026-10-14
 */

import { $ } from "../../utils/dom.js";

export function editorEl() {
  return $("input");
}

/** 序列化编辑器内容：文本原样，chip → `![name](url)`，块级/换行节点 → \n */
export function editorText() {
  const editor = editorEl();
  if (!editor) return "";
  let out = "";
  const walk = (node) => {
    for (const child of node.childNodes) {
      if (child.nodeType === Node.TEXT_NODE) {
        out += child.nodeValue;
      } else if (child.dataset?.attachId) {
        const name = (child.dataset.attachName || "image").replace(/[[\]()\n]/g, "_");
        out += `![${name}](${child.dataset.attachUrl})`;
      } else if (child.tagName === "BR") {
        out += "\n";
      } else {
        // div 等块级行：首行之外补换行
        if (out && !out.endsWith("\n")) out += "\n";
        walk(child);
      }
    }
  };
  walk(editor);
  return out.replace(/\u00A0/g, " ");
}

/** 是否有可发送内容：非空文本或至少一个附件 chip */
export function editorHasContent() {
  const editor = editorEl();
  if (!editor) return false;
  if (editor.querySelector("[data-attach-id]")) return true;
  return editor.textContent.trim().length > 0;
}

/** 清空编辑器（发送后调用；chips 已随消息发出，不触发服务端删除） */
export function clearEditor() {
  const editor = editorEl();
  if (!editor) return;
  editor.textContent = "";
  syncPlaceholder();
}

/** 在光标处插入节点；编辑器未聚焦时追加到末尾 */
export function insertNodeAtCaret(node) {
  const editor = editorEl();
  if (!editor) return;
  const sel = window.getSelection();
  let range = sel?.rangeCount ? sel.getRangeAt(0) : null;
  if (!range || !editor.contains(range.commonAncestorContainer)) {
    editor.appendChild(node);
  } else {
    range.deleteContents();
    range.insertNode(node);
  }
  const space = document.createTextNode(" ");
  node.after(space);
  const after = document.createRange();
  after.setStart(space, 1);
  after.collapse(true);
  sel.removeAllRanges();
  sel.addRange(after);
  syncPlaceholder();
}

/** 粘贴纯文本（多行拆成 <br>），优先走 execCommand 以获得原生换行处理 */
export function insertPlainText(text) {
  const editor = editorEl();
  if (!editor || !text) return;
  editor.focus();
  if (!document.execCommand("insertText", false, text)) {
    const sel = window.getSelection();
    const range = sel?.rangeCount ? sel.getRangeAt(0) : null;
    const target = range && editor.contains(range.commonAncestorContainer) ? range : null;
    const lines = text.split("\n");
    const frag = document.createDocumentFragment();
    lines.forEach((line, i) => {
      if (i > 0) frag.appendChild(document.createElement("br"));
      if (line) frag.appendChild(document.createTextNode(line));
    });
    if (target) {
      target.deleteContents();
      target.insertNode(frag);
    } else {
      editor.appendChild(frag);
    }
  }
  syncPlaceholder();
}

/** 占位符：无文本且无 chip 时加 is-empty */
export function syncPlaceholder() {
  const editor = editorEl();
  if (!editor) return;
  editor.classList.toggle("is-empty", !editorHasContent());
}

/* 发送清空等场景下，chip 被移除但不删服务端文件（文件已随消息发出） */
const keepOnRemove = new Set();

/** 清空前登记现存 chip：随后的 DOM 移除不再触发服务端删除 */
export function keepAttachmentsOnClear() {
  editorEl()?.querySelectorAll("[data-attach-id]")
    .forEach((n) => keepOnRemove.add(n.dataset.attachId));
}

/** chip 移除时是否跳过服务端删除（一次性消费） */
export function skipServerDelete(fileId) {
  return keepOnRemove.delete(fileId);
}

/** 程序化摘除 chip 且不删服务端文件前，先登记 fileId */
export function markKeepOnRemove(fileId) {
  keepOnRemove.add(fileId);
}
