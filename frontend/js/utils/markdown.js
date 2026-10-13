/**
 * Markdown 解析与渲染工具
 */

import { el, icon } from "./dom.js";
import { formatLangName, highlightHtml } from "./highlight.js";

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
    const code = pre.querySelector("code");
    const rawClass = code ? code.className : "";
    const m = /(?:language|lang)-([\w-]+)/i.exec(rawClass);
    const lang = m ? m[1] : "";

    const wrap = el("div", "code-block");
    const header = el("div", "code-header");
    const langLabel = el("span", "code-lang", formatLangName(lang));
    header.appendChild(langLabel);

    const copyBtn = el("button", "code-copy");
    copyBtn.type = "button";
    copyBtn.title = "复制代码";
    copyBtn.setAttribute("aria-label", "复制代码");
    copyBtn.appendChild(icon("copy"));
    header.appendChild(copyBtn);

    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(header);
    wrap.appendChild(pre);

    if (code) {
      code.innerHTML = highlightHtml(code.textContent, lang);
    }
  }
}

export function setMarkdown(node, text) {
  if (!node) return;
  node.innerHTML = renderMarkdown(text);
  enhanceCodeBlocks(node);
}
