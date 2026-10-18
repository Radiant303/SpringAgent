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

export function appendKeepingBottom(element, text, threshold = 40) {
  const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < threshold;
  element.textContent += text;
  if (atBottom) {
    element.scrollTop = element.scrollHeight;
    markScrolling(element);
  }
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
  edit: '<path d="M4.5 19.5h4L19.5 8.5l-4-4L4.5 15.5v4z"/><path d="M14 6l4 4"/>',
  file: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  search: '<circle cx="10.75" cy="10.75" r="6.5"/><path d="M19.75 19.75l-4.2-4.2"/>',
  link: '<path d="M10 13a4.5 4.5 0 0 0 6.4 0l2.5-2.5a4.5 4.5 0 0 0-6.4-6.4l-1.4 1.4"/><path d="M14 11a4.5 4.5 0 0 0-6.4 0l-2.5 2.5a4.5 4.5 0 0 0 6.4 6.4l1.4-1.4"/>',
  terminal: '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 9l3 3-3 3M13 15h4"/>',
  question: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14M12 17h.01"/>',
  tool: '<path d="M14.7 5.38a4 4 0 0 0-5.4 5.4L4 16.08l3 3 5.3-5.3a4 4 0 0 0 5.4-5.4l-2.5 2.5-2.5-.5-.5-2.5z"/>',
  work: '<circle cx="12" cy="12" r="3"/><path d="M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6l1.4 1.4M17 17l1.4 1.4M5.6 18.4L7 17M17 7l1.4-1.4"/>',
  chevron: '<path d="M9 6l6 6-6 6"/>',
  caret: '<path d="M6 9l6 6 6-6"/>',
  check: '<path d="M5 12.25l4.5 4.5L19 7.25"/>',
  planCheck: '<circle cx="12" cy="12" r="10.875" stroke-width="2.25"/><path d="M7.125 12.6L10.65 16.125L17.025 8.85" stroke-width="2.25"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2.5"/><path d="M16 8V6.5A2.5 2.5 0 0 0 13.5 4h-7A2.5 2.5 0 0 0 4 6.5v7A2.5 2.5 0 0 0 6.5 16H8"/>',
  tick: '<path d="M5 12.25l4.5 4.5L19 7.25"/>',
  prev: '<path d="M15 6l-6 6 6 6"/>',
  next: '<path d="M9 6l6 6-6 6"/>',
  first: '<path d="M18 6l-6 6 6 6"/><path d="M11 6l-6 6 6 6"/>',
  last: '<path d="M6 6l6 6-6 6"/><path d="M13 6l6 6-6 6"/>',
  calendar: '<rect x="3.5" y="5.5" width="17" height="15" rx="2.5"/><path d="M3.5 10h17M8 3.5v4M16 3.5v4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  trash: '<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/>',
  zap: '<path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"/>',
  back: '<path d="M15 5l-7 7 7 7"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  plug: '<path d="m19 5 3-3"/><path d="m2 22 3-3"/><path d="M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4Z"/><path d="M7.5 13.5 10 11"/><path d="M10.5 16.5 13 14"/><path d="m12 6 6 6 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0Z"/>',
  arrowDown: '<path d="M12 5v14"/><path d="m19 12-7 7-7-7"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.5-3.5a2 2 0 0 0-2.8 0L6 20"/>',
  toolPlan: '<circle cx="12" cy="12" r="8"/><path d="m8 12 2.5 2.5L16 9"/>',
  toolTrash: '<path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7v11a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7M10 10.5v6M14 10.5v6"/>',
  toolImage: '<rect x="4" y="4" width="16" height="16" rx="2"/><circle cx="9" cy="9" r="1.5"/><path d="m5 18 5-5 3 3 3-3 4 4"/>',
  toolPlug: '<path d="M9 3v5M15 3v5M5 8h14M6 8v4a6 6 0 0 0 12 0V8M12 18v3"/>',
  toolCreateDirectory: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7zM9 13h6M12 10v6"/>',
  toolWebSearch: '<circle cx="12" cy="12" r="8.5"/><ellipse cx="12" cy="12" rx="3.5" ry="8.5"/><path d="M3.5 12h17"/>',
  toolCurrentSession: '<path d="M6 4.9h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-5l-3 2.2V16.9H6a2 2 0 0 1-2-2V6.9a2 2 0 0 1 2-2z"/>',
  toolStartCommand: '<circle cx="12" cy="12" r="8.5"/><path d="m10 8.5 5 3.5-5 3.5z"/>',
  toolCheckCommand: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7v5l3 2"/>',
  toolStopCommand: '<circle cx="12" cy="12" r="8.5"/><rect x="9" y="9" width="6" height="6" rx=".75"/>',
  toolCreateUnit: '<path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2V5M9 10h6M12 7v6"/>',
  toolTeachState: '<rect x="4" y="4" width="16" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
  toolUpdateMastery: '<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4"/><path d="M12 12h.01"/>',
  toolBumpVersion: '<path d="M4 12a8 8 0 1 0 2.343146-5.656854L4 8M4 4v4h4M12 8v4l3 2"/>',
  toolReadPlan: '<path d="M5 6h14M5 12h14M5 18h14"/>',
  toolUpdateStatuses: '<path d="M4 7l2 2 3-4M12 7h8M4 17l2 2 3-4M12 17h8"/>',
  toolRemoveTask: '<path d="M5 12h14"/>',
  toolDelegateTask: '<circle cx="12" cy="5.5" r="2.5"/><circle cx="6" cy="18.5" r="2.5"/><circle cx="18" cy="18.5" r="2.5"/><path d="M12 8v4M6 16v-4h12v4"/>',
  close: '<path d="M18 6 6 18M6 6l12 12"/>',
};

export function icon(name, className) {
  const span = el("span", "icon" + (className ? " " + className : ""));
  span.innerHTML = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${ICONS[name] || ""}</svg>`;
  return span;
}
