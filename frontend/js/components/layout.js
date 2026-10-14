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
      <a class="um-item hidden" id="um-settings" href="/static/admin.html#registration" role="menuitem">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>
        <span>系统设置</span>
      </a>
      <a class="um-item hidden" id="um-community" href="#" target="_blank" rel="noopener" role="menuitem">
        <svg viewBox="0 0 192 192" fill="none" stroke="currentColor" stroke-width="12" stroke-linejoin="round" aria-hidden="true"><path d="M153.933 114.718c-2.953-9.509-6.349-17.5-11.573-30.58C143.172 49.786 128.922 22 96.101 22c-33.189 0-47.125 28.34-46.24 62.139-5.241 13.097-8.62 21.033-11.573 30.579-6.276 20.256-4.246 28.636-2.695 28.821 3.323.407 12.94-15.243 12.94-15.243 0 9.064 4.651 20.886 14.73 29.413-4.873 1.499-15.82 5.532-13.217 9.953 2.105 3.57 36.217 2.275 46.056 1.165 9.838 1.11 43.95 2.405 46.055-1.165 2.602-4.403-8.362-8.454-13.217-9.953 10.078-8.546 14.73-20.367 14.73-29.413 0 0 9.617 15.65 12.94 15.243 1.569-.203 3.6-8.583-2.677-28.821Z"/></svg>
        <span>官方社群</span>
      </a>
      <a class="um-item" href="https://github.com/Radiant303/SpringHarness" target="_blank" rel="noopener" role="menuitem">
        <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M10.226 17.284c-2.965-.36-5.054-2.493-5.054-5.256 0-1.123.404-2.336 1.078-3.144-.292-.741-.247-2.314.09-2.965.898-.112 2.111.36 2.83 1.01.853-.269 1.752-.404 2.853-.404 1.1 0 1.999.135 2.807.382.696-.629 1.932-1.1 2.83-.988.315.606.36 2.179.067 2.942.72.854 1.101 2 1.101 3.167 0 2.763-2.089 4.852-5.098 5.234.763.494 1.28 1.572 1.28 2.807v2.336c0 .674.561 1.056 1.235.786 4.066-1.55 7.255-5.615 7.255-10.646C23.5 6.188 18.334 1 11.978 1 5.62 1 .5 6.188.5 12.545c0 4.986 3.167 9.12 7.435 10.669.606.225 1.19-.18 1.19-.786V20.63a2.9 2.9 0 0 1-1.078.224c-1.483 0-2.359-.808-2.987-2.313-.247-.607-.517-.966-1.034-1.033-.27-.023-.359-.135-.359-.27 0-.27.45-.471.898-.471.652 0 1.213.404 1.797 1.235.45.651.921.943 1.483.943.561 0 .92-.202 1.437-.719.382-.381.674-.718.944-.943"/></svg>
        <span>项目地址</span>
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
