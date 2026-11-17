/**
 * 聊天页响应式布局与侧栏抽屉。
 */

import { $ } from "../../utils/dom.js";

export const mobileLayout = window.matchMedia("(max-width: 760px)");

/** 顶栏控件与正文列的最小间距（像素） */
const TOPBAR_CONTENT_GAP = 24;
let topbarFrame = 0;

export function isMobileLayout() {
  return mobileLayout.matches;
}

function syncTopbarMode() {
  const main = $("main");
  const topbar = $("topbar");
  const messages = $("messages");
  if (!main || !topbar || !messages) return;
  if (isMobileLayout()) {
    main.classList.remove("topbar-immersive");
    return;
  }
  const messagesRect = messages.getBoundingClientRect();
  const messagesStyle = getComputedStyle(messages);
  const contentLeft = messagesRect.left + parseFloat(messagesStyle.paddingLeft);
  const contentRight = messagesRect.right - parseFloat(messagesStyle.paddingRight);
  const contentCenter = (contentLeft + contentRight) / 2;
  const items = [...topbar.children].filter((item) => !item.matches(".spacer, #model-topbar-slot"));
  const fits = items.every((item) => {
    const style = getComputedStyle(item);
    if (style.display === "none" || style.visibility === "hidden") return true;
    const rect = item.getBoundingClientRect();
    if (!rect.width) return true;
    return rect.left + rect.width / 2 < contentCenter
      ? rect.right <= contentLeft - TOPBAR_CONTENT_GAP
      : rect.left >= contentRight + TOPBAR_CONTENT_GAP;
  });
  main.classList.toggle("topbar-immersive", fits);
}

function scheduleTopbarMode() {
  if (topbarFrame) return;
  topbarFrame = requestAnimationFrame(() => {
    topbarFrame = 0;
    syncTopbarMode();
  });
}

function syncSidebar() {
  const app = $("app");
  const sidebar = $("sidebar");
  const main = $("main");
  if (!app || !sidebar || !main) return;
  const mobile = isMobileLayout();
  if (!mobile) app.classList.remove("sidebar-open");
  const open = mobile
    ? app.classList.contains("sidebar-open")
    : !app.classList.contains("sidebar-collapsed");
  sidebar.inert = !open;
  sidebar.setAttribute("aria-hidden", String(!open));
  main.inert = mobile && open;
  $("expand-sidebar")?.setAttribute("aria-expanded", String(mobile && open));
  if (!open && sidebar.contains(document.activeElement)) {
    $("expand-sidebar")?.focus({ preventScroll: true });
  }
  scheduleTopbarMode();
}

function setSidebarOpen(open, restoreFocus = true) {
  const app = $("app");
  if (!app) return;
  if (isMobileLayout()) app.classList.toggle("sidebar-open", open);
  else app.classList.toggle("sidebar-collapsed", !open);
  syncSidebar();
  if (!isMobileLayout()) return;
  if (open) $("toggle-sidebar")?.focus({ preventScroll: true });
  else if (restoreFocus) $("expand-sidebar")?.focus({ preventScroll: true });
}

export function closeMobileSidebar() {
  if (!isMobileLayout() || !$("app")?.classList.contains("sidebar-open")) return false;
  setSidebarOpen(false, false);
  return true;
}

export function toggleSidebar() {
  const app = $("app");
  if (!app) return;
  const open = isMobileLayout()
    ? !app.classList.contains("sidebar-open")
    : app.classList.contains("sidebar-collapsed");
  setSidebarOpen(open);
}

function syncModelLocation() {
  const pill = $("model-pill");
  const slot = $("model-topbar-slot");
  const toolbar = $("input-toolbar");
  if (!pill || !slot || !toolbar) return;
  if (isMobileLayout()) {
    if (pill.parentElement !== slot) slot.appendChild(pill);
  } else if (pill.parentElement !== toolbar) {
    toolbar.insertBefore(pill, $("send-btn"));
  }
}

function syncLayout() {
  syncSidebar();
  syncModelLocation();
}

export function initResponsiveLayout() {
  if ($("toggle-sidebar")) $("toggle-sidebar").onclick = toggleSidebar;
  if ($("expand-sidebar")) $("expand-sidebar").onclick = toggleSidebar;
  if ($("sidebar-backdrop")) $("sidebar-backdrop").onclick = () => setSidebarOpen(false);
  mobileLayout.addEventListener("change", syncLayout);
  syncLayout();

  if (typeof ResizeObserver !== "undefined") {
    const observer = new ResizeObserver(scheduleTopbarMode);
    for (const item of [$("main"), $("topbar"), $("messages"), $("breadcrumb"), $("collapsed-actions")]) {
      if (item) observer.observe(item);
    }
  }
  if (typeof MutationObserver !== "undefined") {
    const observer = new MutationObserver(scheduleTopbarMode);
    if ($("session-title")) observer.observe($("session-title"), { childList: true, characterData: true, subtree: true });
    if ($("messages")) observer.observe($("messages"), { childList: true });
    if ($("main")) observer.observe($("main"), { attributes: true, attributeFilter: ["class"] });
  }
  document.fonts?.ready.then(scheduleTopbarMode);
}
