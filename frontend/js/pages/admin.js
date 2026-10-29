/**
 * 管理后台主入口
 */

import { $ } from "../utils/dom.js";
import { getToken, getUsername, getRole, isStaff, goLogin, logout, ROLE_LABELS } from "../api/auth.js";
import { UI } from "../components/ui.js";
import { initRipple } from "../components/ripple.js";
import { registerPageLoader, switchPage, pageFromHash } from "./admin/nav.js";
import { loadAccount, openChangePassword, onBindEmail } from "./admin/account.js";
import { loadMyPoints, onUserRedeem } from "./admin/points.js";
import { loadKnowledge, bindKnowledge } from "./admin/knowledge.js";
import { ensureUsers, loadUsers, onUserAction, onUsersUpdated } from "./admin/users.js";
import { loadUsage, renderUsageUserOptions } from "./admin/usage.js";
import { loadUsageRecords, loadMyUsage, renderUsageRecordsUserOptions } from "./admin/usageRecords.js";
import { loadLedger, renderLedgerUserOptions } from "./admin/ledger.js";
import { loadModels, onModelAction, onModelFormSubmit, fillModelForm } from "./admin/models.js";
import { loadModelConfig, bindModelConfig } from "./admin/modelConfig.js";
import { loadFailureEvents, bindFailureEvents } from "./admin/failureEvents.js";
import { loadRedeemCodes, openRedeemGen, onRedeemAction } from "./admin/redeem.js";
import {
  loadRegistration, saveRegistration,
  loadStorage, saveStorage, bindStorageSettings,
  loadPlatform, savePlatform,
  loadBilling, saveBilling,
  loadCommunity, saveCommunity,
  loadSearch, saveSearch, bindSearchSettings,
} from "./admin/settings.js";

// 注册各页数据加载器
registerPageLoader("account", loadAccount);
registerPageLoader("knowledge", loadKnowledge);
registerPageLoader("points", loadMyPoints);
registerPageLoader("my-usage", loadMyUsage);
registerPageLoader("users", () => loadUsers());
registerPageLoader("usage", () => ensureUsers().then(() => loadUsage()));
registerPageLoader("usage-records", () => ensureUsers().then(() => loadUsageRecords()));
registerPageLoader("ledger", () => ensureUsers().then(() => loadLedger()));
registerPageLoader("models", () => loadModels());
registerPageLoader("model-config", () => loadModelConfig());
registerPageLoader("redeem", () => ensureUsers().then(() => loadRedeemCodes()));
registerPageLoader("registration", () => loadRegistration());
registerPageLoader("storage", () => loadStorage());
registerPageLoader("platform", () => loadPlatform());
registerPageLoader("billing", () => loadBilling());
registerPageLoader("community", () => loadCommunity());
registerPageLoader("search", loadSearch);
registerPageLoader("failure-events", () => ensureUsers().then(() => loadFailureEvents()));

// 用户列表加载或刷新后，同步用量统计和积分流水页的用户筛选下拉框
onUsersUpdated((list) => {
  renderUsageUserOptions(list);
  renderUsageRecordsUserOptions(list);
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
  if (role === "owner") {
    $("nav-group-system")?.classList.remove("hidden");
    $("nav-failure-events")?.classList.remove("hidden");
  }

  document.querySelectorAll("#admin-nav .nav-item").forEach((el) => {
    el.onclick = () => switchPage(el.dataset.page);
  });

  // 下拉框与日期框换成自绘控件
  UI.enhance();
  initRipple();

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
  bindKnowledge();

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
    if ($("usage-records-query")) $("usage-records-query").onclick = () => loadUsageRecords(1).catch((e) => UI.toast(e.message, { type: "error" }));
    if ($("ledger-query")) $("ledger-query").onclick = () => loadLedger(1).catch((e) => UI.toast(e.message, { type: "error" }));
    $("redeem-status")?.addEventListener("change", () => loadRedeemCodes(1).catch((e) => UI.toast(e.message, { type: "error" })));

    if (role === "owner") {
      $("model-form")?.addEventListener("submit", onModelFormSubmit);
      if ($("model-cancel")) $("model-cancel").onclick = () => fillModelForm(null);
      if ($("registration-save")) $("registration-save").onclick = saveRegistration;
      if ($("storage-save")) $("storage-save").onclick = saveStorage;
      if ($("platform-save")) $("platform-save").onclick = savePlatform;
      if ($("billing-save")) $("billing-save").onclick = saveBilling;
      if ($("community-save")) $("community-save").onclick = saveCommunity;
      if ($("search-save")) $("search-save").onclick = saveSearch;
      bindSearchSettings();
      bindStorageSettings();
      bindFailureEvents();
      if ($("redeem-open-gen")) $("redeem-open-gen").onclick = () => openRedeemGen().catch((e) => UI.toast(e.message, { type: "error" }));

      // 模型配置页（默认/子代理模型 + Provider 两级管理）
      bindModelConfig();
    } else {
      // 资费卡查看与兑换码列表对管理员开放，编辑表单与生成/作废仅站长
      $("model-form")?.remove();
      $("redeem-open-gen")?.remove();
    }
  }

  // 用户侧兑换（账单与用量页，所有角色可用）
  if ($("user-redeem-btn")) $("user-redeem-btn").onclick = onUserRedeem;
  if ($("my-usage-query")) $("my-usage-query").onclick = () => loadMyUsage(1).catch((e) => UI.toast(e.message, { type: "error" }));
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
