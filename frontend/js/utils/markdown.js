/**
 * Markdown 解析与渲染工具。
 *
 * @author hanbing
 * @since 2026-10-13
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
  const template = document.createElement("template");
  template.innerHTML = DOMPurify ? DOMPurify.sanitize(rawHtml) : rawHtml;
  for (const link of template.content.querySelectorAll("a[href]")) {
    link.target = "_blank";
    link.rel = "noopener noreferrer";
  }
  return template.innerHTML;
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
    copyBtn.appendChild(icon("copy", "icon-copy"));
    copyBtn.appendChild(icon("tick", "icon-tick"));
    header.appendChild(copyBtn);

    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(header);
    wrap.appendChild(pre);

    if (code) {
      code.innerHTML = highlightHtml(code.textContent, lang);
      code.classList.add("hljs");
    }

    setupCodeScrollbar(wrap, pre);
  }
}

/**
 * 装配代码块横向滚动条
 *
 * @param wrap 代码块容器
 * @param pre 内部 pre 元素
 */
function setupCodeScrollbar(wrap, pre) {
  const bar = el("div", "code-scrollbar");
  const thumb = el("div", "code-scrollbar-thumb");
  bar.appendChild(thumb);
  wrap.appendChild(bar);

  const update = () => {
    const sw = pre.scrollWidth;
    const cw = pre.clientWidth;
    if (sw <= cw + 2) {
      wrap.classList.remove("has-scroll");
      return;
    }
    wrap.classList.add("has-scroll");
    const trackWidth = bar.clientWidth || (wrap.clientWidth - 36);
    if (trackWidth <= 0) return;
    const ratio = cw / sw;
    const thumbWidth = Math.max(28, trackWidth * ratio);
    const maxScroll = sw - cw;
    const scrollRatio = maxScroll > 0 ? Math.max(0, Math.min(1, pre.scrollLeft / maxScroll)) : 0;
    const maxThumbLeft = trackWidth - thumbWidth;
    const thumbLeft = scrollRatio * maxThumbLeft;

    thumb.style.width = `${thumbWidth}px`;
    thumb.style.transform = `translateX(${thumbLeft}px)`;
  };

  pre.addEventListener("scroll", update, { passive: true });

  let dragging = false;
  let startX = 0;
  let startScrollLeft = 0;

  thumb.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    e.stopPropagation();
    dragging = true;
    startX = e.clientX;
    startScrollLeft = pre.scrollLeft;
    thumb.classList.add("is-dragging");
    try {
      thumb.setPointerCapture(e.pointerId);
    } catch {}
  });

  const onPointerMove = (e) => {
    if (!dragging) return;
    const dx = e.clientX - startX;
    const trackWidth = bar.clientWidth || (wrap.clientWidth - 36);
    const thumbWidth = thumb.offsetWidth || 28;
    const maxThumbLeft = trackWidth - thumbWidth;
    if (maxThumbLeft <= 0) return;
    const maxScroll = pre.scrollWidth - pre.clientWidth;
    pre.scrollLeft = startScrollLeft + (dx / maxThumbLeft) * maxScroll;
  };

  thumb.addEventListener("pointermove", onPointerMove);

  const stopDrag = (e) => {
    if (!dragging) return;
    dragging = false;
    thumb.classList.remove("is-dragging");
    if (e && e.pointerId) {
      try {
        thumb.releasePointerCapture(e.pointerId);
      } catch {}
    }
  };

  thumb.addEventListener("pointerup", stopDrag);
  thumb.addEventListener("pointercancel", stopDrag);
  window.addEventListener("pointerup", stopDrag);

  bar.addEventListener("pointerdown", (e) => {
    if (e.target === thumb) return;
    const rect = bar.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const trackWidth = rect.width;
    const thumbWidth = thumb.offsetWidth || 28;
    const maxThumbLeft = trackWidth - thumbWidth;
    if (maxThumbLeft > 0) {
      const targetThumbLeft = Math.max(0, Math.min(maxThumbLeft, clickX - thumbWidth / 2));
      const maxScroll = pre.scrollWidth - pre.clientWidth;
      pre.scrollLeft = (targetThumbLeft / maxThumbLeft) * maxScroll;
    }
  });

  bar.addEventListener("wheel", (e) => {
    const delta = e.deltaX || e.deltaY;
    if (delta) {
      pre.scrollLeft += delta;
      e.preventDefault();
    }
  }, { passive: false });

  if (typeof ResizeObserver !== "undefined") {
    const ro = new ResizeObserver(update);
    ro.observe(pre);
    ro.observe(wrap);
  }

  update();
  requestAnimationFrame(update);
  setTimeout(update, 50);
}

export function setMarkdown(node, text, enhance = true) {
  if (!node) return;
  node.innerHTML = renderMarkdown(text);
  if (enhance) enhanceCodeBlocks(node);
}
