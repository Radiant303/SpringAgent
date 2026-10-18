/**
 * 管理后台导航与页面切换路由
 */

import { $ } from "../../utils/dom.js";
import { getRole, isStaff } from "../../api/auth.js";
import { UI } from "../../components/ui.js";

export const PAGE_TITLES = {
  account: "账号与安全",
  points: "账单与用量",
  "my-usage": "使用日志",
  users: "用户管理",
  usage: "用量统计",
  "usage-records": "使用日志",
  ledger: "积分流水",
  models: "模型资费",
  "model-config": "模型配置",
  redeem: "兑换码",
  registration: "注册设置",
  storage: "存储设置",
  platform: "平台设置",
  billing: "计费设置",
  community: "社群设置",
  search: "搜索服务",
};

export const pageLoaders = {};

/** 仅站长的页面 */
const OWNER_PAGES = new Set(["model-config", "registration", "storage", "platform", "billing", "community", "search"]);
/** 站长/管理员的页面 */
const STAFF_PAGES = new Set(["users", "usage", "usage-records", "ledger", "models", "redeem"]);

export function registerPageLoader(name, loaderFn) {
  pageLoaders[name] = loaderFn;
}

export function pageAllowed(page) {
  const role = getRole();
  const staff = isStaff();
  if (!Object.hasOwn(pageLoaders, page)) return false;
  if (OWNER_PAGES.has(page)) return role === "owner";
  if (STAFF_PAGES.has(page)) return staff;
  return true;
}

export function pageFromHash() {
  const page = decodeURIComponent(location.hash.slice(1));
  return pageAllowed(page) ? page : "account";
}

export function switchPage(page) {
  if (!pageAllowed(page)) page = "account";
  document.querySelectorAll("#admin-nav .nav-item").forEach((el) =>
    el.classList.toggle("active", el.dataset.page === page));
  document.querySelectorAll(".admin-page").forEach((el) =>
    el.classList.toggle("active", el.dataset.page === page));
  document.title = (PAGE_TITLES[page] || "账户中心") + " - Spring Harness";
  if (location.hash !== "#" + page) history.replaceState(null, "", "#" + page);
  const main = $("admin-main");
  if (main) main.scrollTop = 0;
  
  const loader = pageLoaders[page];
  const section = document.querySelector(`.admin-page[data-page="${page}"]`);
  section?.querySelectorAll("tbody").forEach((tb) => { if (!tb.rows.length) UI.tableSkeleton(tb); });
  if (loader) {
    loader().catch((e) => {
      if (section) UI.settleSkeletons(section);
      UI.toast(e.message, { type: "error" });
    });
  }
}
