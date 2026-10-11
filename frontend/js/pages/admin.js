/**
 * 管理后台主入口
 */

import { $ } from "../utils/dom.js";
import { getToken, getUsername, getRole, isStaff, goLogin, logout, ROLE_LABELS } from "../api/auth.js";
import { UI } from "../components/ui.js";
import { registerPageLoader, switchPage, pageFromHash } from "./admin/nav.js";
import { loadAccount, openChangePassword, onBindEmail } from "./admin/account.js";
import { loadMyPoints, onUserRedeem } from "./admin/points.js";
import { ensureUsers, loadUsers, onUserAction, onUsersUpdated } from "./admin/users.js";
import { loadUsage, renderUsageUserOptions } from "./admin/usage.js";
import { loadLedger, renderLedgerUserOptions } from "./admin/ledger.js";
import { loadModels, onModelAction, onModelFormSubmit, fillModelForm } from "./admin/models.js";
import {
  loadModelConfig,
  onMcProviderSubmit,
  fillMcProviderForm,
  onMcProviderAction,
  onMcModelSubmit,
  fillMcModelForm,
  onMcModelAction,
  onMcDefaultChange,
  onMcSubagentChange,
} from "./admin/modelConfig.js";
import { loadRedeemCodes, openRedeemGen, onRedeemAction } from "./admin/redeem.js";
import { loadSettings, saveSettings } from "./admin/settings.js";

// 注册各页数据加载器
registerPageLoader("account", loadAccount);
registerPageLoader("points", loadMyPoints);
registerPageLoader("users", () => loadUsers());
registerPageLoader("usage", () => ensureUsers().then(() => loadUsage()));
registerPageLoader("ledger", () => ensureUsers().then(() => loadLedger()));
registerPageLoader("models", () => loadModels());
registerPageLoader("model-config", () => loadModelConfig());
registerPageLoader("redeem", () => ensureUsers().then(() => loadRedeemCodes()));
registerPageLoader("settings", () => loadSettings());

// 用户列表加载或刷新后，同步用量统计和积分流水页的用户筛选下拉框
onUsersUpdated((list) => {
  renderUsageUserOptions(list);
  renderLedgerUserOptions(list);
});

export function init() {
  if (!getToken()) {
    goLogin();
    return;
  }

  const role = getRole();
  const staff = isStaff();
  const myName = getUsername();

  // 分组显隐：管理组对站长/管理员开放，系统组仅站长
  if (staff) $("nav-group-admin")?.classList.remove("hidden");
  if (role === "owner") $("nav-group-system")?.classList.remove("hidden");

  document.querySelectorAll("#admin-nav .nav-item").forEach((el) => {
    el.onclick = () => switchPage(el.dataset.page);
  });

  // 下拉框与日期框换成自绘控件
  UI.enhance();

  // 账号页静态信息
  if ($("account-name")) $("account-name").textContent = myName;
  if ($("account-username")) $("account-username").textContent = myName;
  if ($("account-role")) {
    $("account-role").textContent = ROLE_LABELS[role] || role;
    $("account-role").classList.add(role);
  }
  if ($("account-role-text")) $("account-role-text").textContent = ROLE_LABELS[role] || role;
  if ($("account-logout")) {
    $("account-logout").onclick = logout;
  }

  // 账户安全（所有角色可用）：修改密码与绑定/换绑邮箱
  if ($("ac-change-pw")) $("ac-change-pw").onclick = openChangePassword;
  if ($("ac-bind-email")) $("ac-bind-email").onclick = onBindEmail;

  if (staff) {
    $("user-tbody")?.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-act]");
      if (btn) onUserAction(btn);
    });
    $("model-tbody")?.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-act]");
      if (btn) onModelAction(btn);
    });
    $("redeem-tbody")?.addEventListener("click", (e) => {
      const btn = e.target.closest("button[data-act]");
      if (btn) onRedeemAction(btn);
    });
    if ($("usage-query")) $("usage-query").onclick = () => loadUsage(1).catch((e) => UI.toast(e.message, { type: "error" }));
    if ($("ledger-query")) $("ledger-query").onclick = () => loadLedger(1).catch((e) => UI.toast(e.message, { type: "error" }));
    $("redeem-status")?.addEventListener("change", () => loadRedeemCodes(1).catch((e) => UI.toast(e.message, { type: "error" })));

    if (role === "owner") {
      $("model-form")?.addEventListener("submit", onModelFormSubmit);
      if ($("model-cancel")) $("model-cancel").onclick = () => fillModelForm(null);
      if ($("settings-save")) $("settings-save").onclick = saveSettings;
      if ($("redeem-open-gen")) $("redeem-open-gen").onclick = openRedeemGen;

      // 模型配置页（Provider / 模型定义 / 默认模型）
      $("mc-provider-form")?.addEventListener("submit", onMcProviderSubmit);
      if ($("mc-p-cancel")) $("mc-p-cancel").onclick = () => fillMcProviderForm(null);
      $("mc-provider-tbody")?.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-act]");
        if (btn) onMcProviderAction(btn);
      });
      $("mc-model-form")?.addEventListener("submit", onMcModelSubmit);
      if ($("mc-m-cancel")) $("mc-m-cancel").onclick = () => fillMcModelForm(null);
      $("mc-model-tbody")?.addEventListener("click", (e) => {
        const btn = e.target.closest("button[data-act]");
        if (btn) onMcModelAction(btn);
      });
      $("mc-default")?.addEventListener("change", onMcDefaultChange);
      $("mc-sub-searcher")?.addEventListener("change", () => onMcSubagentChange("searcher"));
      $("mc-sub-reviewer")?.addEventListener("change", () => onMcSubagentChange("reviewer"));
    } else {
      // 资费卡查看与兑换码列表对管理员开放，编辑表单与生成/作废仅站长
      $("model-form")?.remove();
      $("redeem-open-gen")?.remove();
    }
  }

  // 用户侧兑换（账单与用量页，所有角色可用）
  if ($("user-redeem-btn")) $("user-redeem-btn").onclick = onUserRedeem;
  $("user-redeem-input")?.addEventListener("keydown", (e) => {
    if (e.key === "Enter") onUserRedeem();
  });

  // 页内链接与浏览器前进后退走 hash
  window.addEventListener("hashchange", () => switchPage(pageFromHash()));
  switchPage(pageFromHash());
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
