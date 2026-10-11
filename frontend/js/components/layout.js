/**
 * 公共布局与可复用 HTML 结构动态注入器
 */

import { el, $ } from "../utils/dom.js";
import { getUsername, getRole, ROLE_LABELS, logout } from "../api/auth.js";

/**
 * 注入品牌标页头（登录页、独立页）
 */
export function renderBrandHeader(container, { brandName = "SPRING HARNESS", logoSrc = "/static/assets/images/log.png" } = {}) {
  const host = $(container);
  if (!host) return;
  host.innerHTML = `
    <img class="login-logo" src="${logoSrc}" alt="">
    <span class="login-brand">${brandName}</span>
  `;
}

/**
 * 注入返回导航链接
 */
export function renderBackLink(container, { href = "/static/index.html", label = "返回" } = {}) {
  const host = $(container);
  if (!host) return;
  host.innerHTML = `
    <a id="back-link" href="${href}">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M15 5l-7 7 7 7"/></svg>
      ${label}
    </a>
  `;
}

/**
 * 渲染侧边栏底部用户面板（积分卡、用户下拉菜单、用户胶囊条）
 */
export function renderSidebarFooter(container, { onPointsClick, onLogout } = {}) {
  const host = $(container);
  if (!host) return;

  host.innerHTML = `
    <a id="points-card" class="points-card hidden" href="/static/admin.html#points">
      <svg class="pc-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><ellipse cx="12" cy="7" rx="7.5" ry="3"/><path d="M4.5 7v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3V7"/><path d="M4.5 12v5c0 1.7 3.4 3 7.5 3s7.5-1.3 7.5-3v-5"/></svg>
      <span class="pc-text">
        <span class="pc-title" id="points-value">积分 <span class="sk" style="--w:56px;--h:12px"></span></span>
        <span class="pc-sub">查看账单与用量</span>
      </span>
      <svg class="pc-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>
    </a>
    <div id="user-menu" class="user-menu hidden" role="menu">
      <a class="um-item" href="/static/admin.html#account" role="menuitem">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="3.8"/><path d="M4.8 20c1.3-3.4 4.1-5 7.2-5s5.9 1.6 7.2 5"/></svg>
        <span>账号与安全</span>
      </a>
      <a class="um-item" href="/static/admin.html#points" role="menuitem">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M6 3.5h12a1 1 0 0 1 1 1v16l-3-2-2 2-2-2-2 2-2-2-3 2v-16a1 1 0 0 1 1-1z"/><path d="M9 8.5h6M9 12.5h6"/></svg>
        <span>账单与用量</span>
      </a>
      <a class="um-item hidden" id="um-admin" href="/static/admin.html#users" role="menuitem">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3.5" y="3.5" width="7" height="7" rx="1.8"/><rect x="13.5" y="3.5" width="7" height="7" rx="1.8"/><rect x="3.5" y="13.5" width="7" height="7" rx="1.8"/><rect x="13.5" y="13.5" width="7" height="7" rx="1.8"/></svg>
        <span>管理后台</span>
        <svg class="um-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M9 6l6 6-6 6"/></svg>
      </a>
      <a class="um-item hidden" id="um-settings" href="/static/admin.html#settings" role="menuitem">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>
        <span>系统设置</span>
      </a>
      <button class="um-item" id="logout-btn" type="button" role="menuitem">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M9.5 20.5H6a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2h3.5"/><path d="M15.5 16.5L20 12l-4.5-4.5M20 12H9.5"/></svg>
        <span>退出登录</span>
      </button>
    </div>
    <div id="user-chip" class="user-chip hidden" title="账号与管理" aria-haspopup="menu">
      <img class="user-avatar" src="/static/assets/images/user_image.png" alt="头像">
      <span id="user-name"></span>
      <span class="user-badge" id="user-badge"></span>
      <svg class="uc-caret" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M8 10l4-4 4 4M8 14l4 4 4-4"/></svg>
    </div>
  `;

  // 绑定退出登录
  const logoutBtn = $("logout-btn");
  if (logoutBtn) {
    logoutBtn.onclick = () => {
      if (onLogout) onLogout();
      else logout();
    };
  }
}
