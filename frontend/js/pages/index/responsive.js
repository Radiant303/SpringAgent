/**
 * 聊天页窄屏布局与侧栏抽屉。
 */

import { $ } from "../../utils/dom.js";

export const mobileLayout = window.matchMedia("(max-width: 760px)");

export function isMobileLayout() {
  return mobileLayout.matches;
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
}
