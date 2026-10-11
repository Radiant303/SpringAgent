/**
 * Markdown 解析与渲染工具
 */

import { el } from "./dom.js";

function getParser() {
  if (typeof window !== "undefined" && window.marked) {
    window.marked.setOptions({ breaks: true, gfm: true });
    return window.marked;
  }
  return null;
}

function getSanitizer() {
  if (typeof window !== "undefined" && window.DOMPurify) {
    return window.DOMPurify;
  }
  return null;
}

export function renderMarkdown(text) {
  const marked = getParser();
  const DOMPurify = getSanitizer();
  const rawHtml = marked ? marked.parse(text || "") : (text || "");
  return DOMPurify ? DOMPurify.sanitize(rawHtml) : rawHtml;
}

export function enhanceCodeBlocks(container) {
  if (!container) return;
  for (const pre of container.querySelectorAll("pre")) {
    if (pre.parentElement && pre.parentElement.classList.contains("code-block")) continue;
    const wrap = document.createElement("div");
    wrap.className = "code-block";
    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(pre);
    wrap.appendChild(el("button", "code-copy", "复制"));
  }
}

export function setMarkdown(node, text) {
  if (!node) return;
  node.innerHTML = renderMarkdown(text);
  enhanceCodeBlocks(node);
}
