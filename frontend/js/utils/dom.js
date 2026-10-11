/**
 * DOM 操作与通用辅助工具
 */

export function $(id) {
  return typeof id === "string" ? document.getElementById(id) : id;
}

export function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined && text !== null) node.textContent = text;
  return node;
}

export function h(tag, className, text) {
  return el(tag, className, text);
}

export function scrollToBottom(container = "messages") {
  const box = $(container);
  if (box) box.scrollTop = box.scrollHeight;
}

export function smoothScrollToBottom(container = "messages") {
  const box = $(container);
  if (box) box.scrollTo({ top: box.scrollHeight, behavior: "smooth" });
}

/**
 * 标记元素正在滚动：滚动条显示，停止 0.9s 后由定时器移除。
 */
export function markScrolling(element) {
  if (!element || !(element instanceof HTMLElement)) return;
  element.classList.add("is-scrolling");
  clearTimeout(element._scrollbarTimer);
  element._scrollbarTimer = setTimeout(() => {
    element.classList.remove("is-scrolling");
  }, 900);
}

export function showPending(container) {
  hidePending();
  const parent = container || $("messages");
  if (!parent) return;
  const node = el("div", "pending-indicator", "请求中…");
  node.id = "pending-indicator";
  parent.appendChild(node);
}

export function hidePending() {
  const node = $("pending-indicator");
  if (node) node.remove();
}

export const ICONS = {
  bulb: '<path d="M9 18h6M10 21h4"/><path d="M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"/>',
  edit: '<path d="M4 20h4L19 9l-4-4L4 16v4z"/><path d="M13.5 6.5l4 4"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/>',
  terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>',
  question: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01"/>',
  book: '<path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z"/><path d="M4 19V5"/>',
  tool: '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3 5.3-5.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.5-.5-.5-2.5z"/>',
  work: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4"/>',
  chevron: '<path d="M9 6l6 6-6 6"/>',
  caret: '<path d="M6 9l6 6 6-6"/>',
  check: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  planCheck: '<circle cx="12" cy="12" r="10.875" stroke-width="2.25"/><path d="M7.125 12.6L10.65 16.125L17.025 8.85" stroke-width="2.25"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V6.5A2.5 2.5 0 0 0 13.5 4h-7A2.5 2.5 0 0 0 4 6.5v7A2.5 2.5 0 0 0 6.5 16H8"/>',
  tick: '<path d="M5 12.5l4.5 4.5L19 7.5"/>',
  prev: '<path d="M15 6l-6 6 6 6"/>',
  next: '<path d="M9 6l6 6-6 6"/>',
  calendar: '<rect x="3.5" y="5" width="17" height="15" rx="2.5"/><path d="M3.5 9.5h17M8 3v4M16 3v4"/>',
};

export function icon(name, className) {
  const span = el("span", "icon" + (className ? " " + className : ""));
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ""}</svg>`;
  return span;
}
