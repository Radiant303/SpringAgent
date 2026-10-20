/**
 * 计费失败事件管理。
 *
 * @author hanbing
 * @since 2026-10-18
 */

import { $, h } from "../../utils/dom.js";
import { fmtPoints, fmtTime } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { getRole } from "../../api/auth.js";
import { UI } from "../../components/ui.js";
import { users } from "./users.js";

const ENDPOINT = "/api/admin/failure-events";
const TYPE_LABELS = { SEARCH_BILLING: "搜索计费", MODEL_SETTLE: "模型结账" };
const SETTLEMENT_LABELS = {
  SETTLED: "已结算",
  UNKNOWN: "用量未知，待核对",
  HELD: "已冻结",
  STALE: "已过期",
  DISCARDED: "已丢弃",
};
const MODEL_RESULT_LABELS = { finished: "已完成", cancelled: "已取消", error: "出错" };
const STATUS_LABELS = {
  PENDING: "待处理",
  RETRYING: "自动重试中",
  SUCCEEDED: "重试成功",
  RESOLVED: "已处理",
  IGNORED: "已忽略",
};
const MANUAL_STATUSES = ["PENDING", "RESOLVED", "IGNORED"];
const MANAGEMENT_NOTICE = "修改状态仅作管理记事，不会触发扣款，不会复位自动重试次数，也不会停止模型结账的真实自动重试。自动重试中或重试成功不可人工修改；积分口径见详情，实际扣款以账务记录为准。";
let page = 1;
let size = 10;
let filters = {};
let listSeq = 0;
let pendingLoad = null;
let loading = null;
let detailSeq = 0;
let detailId = null;
let detailTrigger = null;
let actionBusy = false;
let actionSeq = 0;
let bound = false;

const display = (value) => value == null || value === "" ? "—" : String(value);
const time = (value) => value ? fmtTime(value) : "—";
const statusLabel = (value) => STATUS_LABELS[value] || display(value);
const failureType = (event) => event.failureType || "SEARCH_BILLING";
const isModelSettlement = (event) => failureType(event) === "MODEL_SETTLE";
const typeLabel = (event) => TYPE_LABELS[failureType(event)] || display(failureType(event));
const settlementStatus = (event) => event.settlementStatus;
const settlementLabel = (value) => SETTLEMENT_LABELS[value] || display(value);
const eventStatusLabel = (event) => isModelSettlement(event) && event.status === "PENDING" && settlementStatus(event) === "UNKNOWN" ? "待核对" : statusLabel(event.status);
const statusLocked = (event) => ["RETRYING", "SUCCEEDED"].includes(event.status);
const statusLockMessage = (event) => event.status === "SUCCEEDED" ? "事件已重试成功，不可人工修改" : "事件正在自动重试，请稍后刷新再操作";
const isOwnerPage = () => getRole() === "owner" && document.querySelector('.admin-page[data-page="failure-events"]')?.classList.contains("active");
const reportError = (error) => UI.toast(error?.message || "请求失败，请重试", { type: "error" });

function pointsLabel(event) {
  if (event.pointsReference === true) return "参考积分";
  if (event.pointsReference === false) return "实扣积分";
  return "积分（以账务记录为准）";
}

function points(value) {
  if (value == null || value === "") return "—";
  if (typeof value === "number") return fmtPoints(value);
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(String(value));
  if (!match) return display(value);
  const integer = match[2].replace(/^0+(?=\d)/, "");
  const fraction = (match[3] || "").replace(/0+$/, "");
  return match[1] + integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",") + (fraction ? "." + fraction : "");
}

function cell(tr, value, className) {
  const text = display(value);
  const td = h("td", className, text);
  td.title = text;
  tr.appendChild(td);
  return td;
}

function renderList(data) {
  const nameOf = Object.fromEntries((users || []).map((u) => [u.user_id, u.username]));
  UI.renderRows("failure-tbody", data.list, (tr, event) => {
    tr.replaceChildren();
    cell(tr, time(event.createdAt), "failure-time");
    const userCell = cell(tr, nameOf[event.userId] || event.userId, "failure-user");
    userCell.title = display(event.userId);
    const identifiers = ["事件：" + display(event.eventId)];
    if (isModelSettlement(event)) identifiers.push("请求：" + display(event.requestId));
    const eventCell = cell(tr, "", "failure-event");
    eventCell.replaceChildren(h("span", "role-badge", typeLabel(event)));
    for (const identifier of identifiers) eventCell.appendChild(h("div", "cell-sub mc-row-sub", identifier));
    eventCell.title = [typeLabel(event), ...identifiers].join("\n");
    const pointsCell = cell(tr, points(event.costPoints), "num failure-points");
    pointsCell.title = pointsLabel(event) + "：" + points(event.costPoints);
    cell(tr, eventStatusLabel(event), "failure-status-cell");
    const actions = h("td", "failure-actions");
    for (const [action, label] of [["detail", "详情"], ["status", "标记状态"]]) {
      const button = h("button", "admin-btn", label);
      button.type = "button";
      button.dataset.action = action;
      button.dataset.id = String(event.id);
      if (action === "status" && statusLocked(event)) {
        button.disabled = true;
        button.title = statusLockMessage(event);
      }
      actions.appendChild(button);
    }
    tr.appendChild(actions);
  }, "暂无失败事件");
  UI.renderPager("failure-pager", { page, size, total: data.total }, {
    onPage: (next) => loadFailureEvents(next),
    onSize: (next) => { size = next; loadFailureEvents(1); },
  });
}

async function drainLoads() {
  while (pendingLoad) {
    const request = pendingLoad;
    pendingLoad = null;
    const params = new URLSearchParams({ ...request.filters, page: request.page, size: request.size });
    try {
      const data = await UI.loadTable("failure-tbody", async () => {
        try {
          const result = await api(ENDPOINT + "?" + params);
          if (!result || !Array.isArray(result.list)) throw new Error("失败事件响应格式不正确");
          return result;
        } catch (error) {
          if (request.seq !== listSeq) return null;
          throw error;
        }
      });
      if (request.seq !== listSeq || !isOwnerPage()) continue;
      if (!data.list.length && request.page > 1) {
        const lastPage = Math.max(1, Math.ceil(Number(data.total || 0) / request.size));
        pendingLoad = { ...request, page: Math.min(request.page - 1, lastPage) };
        continue;
      }
      page = Number(data.page) || request.page;
      size = Number(data.size) || request.size;
      renderList(data);
    } catch (error) {
      if (request.seq === listSeq && isOwnerPage()) reportError(error);
    }
  }
}

export function loadFailureEvents(next = page) {
  if (!isOwnerPage()) return Promise.resolve();
  pendingLoad = { seq: ++listSeq, page: next, size, filters: { ...filters } };
  if (!loading) loading = drainLoads().finally(() => { loading = null; });
  return loading;
}

function hideDetail(restoreFocus = false) {
  ++detailSeq;
  detailId = null;
  const dialog = $("failure-detail");
  if (dialog?.open) dialog.close();
  dialog?.removeAttribute("aria-busy");
  $("failure-detail-body")?.replaceChildren();
  if (restoreFocus && detailTrigger?.isConnected) detailTrigger.focus({ preventScroll: true });
  detailTrigger = null;
}

function detailBlock(root, label, value) {
  const section = h("details", "failure-payload");
  section.appendChild(h("summary", null, label));
  section.appendChild(h("pre", null, display(value)));
  root.appendChild(section);
}

function detailFields(root, title, fields) {
  const metadata = h("dl", "failure-meta");
  for (const [label, value] of fields) {
    const row = h("div");
    row.append(h("dt", null, label), h("dd", null, display(value)));
    metadata.appendChild(row);
  }
  root.append(h("h3", "section-caption", title), metadata);
}

function renderDetail(event) {
  const root = $("failure-detail-body");
  const model = isModelSettlement(event);
  const nameOf = Object.fromEntries((users || []).map((u) => [u.user_id, u.username]));
  const userLabel = (id) => nameOf[id] ? `${nameOf[id]}（${id}）` : id;
  root.replaceChildren(h("p", "settings-hint", MANAGEMENT_NOTICE));
  detailFields(root, "公共信息与归属", [
    ["记录 ID", event.id], ["事件类型", typeLabel(event)],
    ["事件 ID", event.eventId], ["用户", userLabel(event.userId)],
    ["会话 ID", event.sessionId], ["轮次 ID", event.turnId],
    ["事件状态", eventStatusLabel(event)], [pointsLabel(event), points(event.costPoints)],
    [model ? "结账失败次数 / 重试上限" : "重试次数 / 上限", display(event.retryCount) + " / " + (model && event.maxRetries == null ? "不限" : display(event.maxRetries))],
    ["下次自动重试", time(event.nextRetryAt)],
    ["创建时间", time(event.createdAt)], ["更新时间", time(event.updatedAt)],
    ["完成时间", time(event.completedAt)],
    ["处理人", userLabel(event.handledBy)], ["处理时间", time(event.handledAt)],
  ]);
  if (model) {
    const settlement = event.settlement || {};
    detailFields(root, "模型结账", [
      ["请求 ID", event.requestId], ["模型 ID", event.modelId],
      ["结账状态", settlementLabel(settlementStatus(event))],
      ["模型请求结果", MODEL_RESULT_LABELS[settlement.status] || display(settlement.status)],
      ["用量是否已知", settlement.usageKnown === true ? "已知" : settlement.usageKnown === false ? "未知，待核对" : "—"],
      ["输入 Token 数", settlement.inputTokens], ["缓存命中 Token 数", settlement.cacheReadTokens],
      ["缓存写入 Token 数", settlement.cacheWriteTokens], ["输出 Token 数", settlement.outputTokens],
      ["联网搜索次数", settlement.webSearches],
    ]);
    root.appendChild(h("p", "settings-hint", "系统会自动重试；这里按每轮结账失败计数，不单独统计一轮里的多次尝试。"));
    if (settlementStatus(event) === "UNKNOWN") {
      root.appendChild(h("p", "settings-hint", "用量未知，结账待核对，不代表重试成功；人工标记不改变冻结单状态。"));
    }
  } else {
    detailFields(root, "搜索计费", [
      ["搜索服务商", event.provider], ["搜索次数", event.searches],
      ["每次参考积分", points(event.unitPoints)],
    ]);
  }
  for (const [label, value] of [["错误类型", event.errorType], ["完整错误", event.errorMessage], ["管理备注", event.remark]]) {
    root.append(h("h3", "section-caption", label), h("pre", "failure-text", display(value)));
  }
  detailBlock(root, "事件原文（UTF-8）", event.rawBody);
  detailBlock(root, "事件原始字节（完整 Base64）", event.rawBodyBase64);
  detailBlock(root, "请求头", typeof event.headers === "object" && event.headers !== null ? JSON.stringify(event.headers, null, 2) : event.headers);
}

async function showDetail(id, trigger) {
  const seq = ++detailSeq;
  detailId = id;
  if (trigger) detailTrigger = trigger;
  const box = $("failure-detail");
  const body = $("failure-detail-body");
  body.replaceChildren();
  for (let i = 0; i < 4; i++) body.appendChild(h("div", "sk failure-detail-sk"));
  box.setAttribute("aria-busy", "true");
  if (!box.open) box.showModal();
  body.scrollTop = 0;
  try {
    const event = await api(ENDPOINT + "/" + encodeURIComponent(id));
    if (seq !== detailSeq || !isOwnerPage()) return;
    if (!event) throw new Error("未找到失败事件");
    renderDetail(event);
  } catch (error) {
    if (seq === detailSeq && isOwnerPage()) {
      body.replaceChildren(h("p", "failure-error", error.message || "详情加载失败，请重新打开"));
    }
  } finally {
    if (seq === detailSeq) box.removeAttribute("aria-busy");
  }
}

async function markStatus(id, trigger) {
  if (actionBusy || !isOwnerPage()) return;
  const seq = ++actionSeq;
  actionBusy = true;
  trigger.disabled = true;
  let locked = false;
  try {
    const readEditableEvent = async () => {
      if (seq !== actionSeq || !isOwnerPage()) throw new Error("页面已切换，请关闭弹窗后重新操作");
      const event = await api(ENDPOINT + "/" + encodeURIComponent(id));
      if (seq !== actionSeq || !isOwnerPage()) throw new Error("页面已切换，请关闭弹窗后重新操作");
      if (!event) throw new Error("未找到失败事件");
      locked = statusLocked(event);
      if (locked) {
        await loadFailureEvents();
        if (detailId === id && isOwnerPage()) await showDetail(id);
        throw new Error(statusLockMessage(event));
      }
      return event;
    };
    const event = await readEditableEvent();
    const options = MANUAL_STATUSES.map((value) => ({ value, label: STATUS_LABELS[value] }));
    if (!MANUAL_STATUSES.includes(event.status)) options.unshift({ value: "", label: "请选择管理状态" });
    const result = await UI.dialog({
      title: "标记失败事件状态",
      message: "类型：" + typeLabel(event) + "\n事件：" + display(event.eventId) + (isModelSettlement(event) ? "\n请求：" + display(event.requestId) : "") + "\n当前状态：" + eventStatusLabel(event) + "\n" + MANAGEMENT_NOTICE,
      okText: "保存标记",
      fields: [
        { name: "status", label: "管理状态", type: "select", value: MANUAL_STATUSES.includes(event.status) ? event.status : "", options },
        { name: "remark", label: "管理备注", type: "textarea", value: event.remark || "", rows: 5, placeholder: "填写处理说明或忽略原因" },
      ],
      validate: (values) => MANUAL_STATUSES.includes(values.status) ? null : { name: "status", message: "请选择有效的管理状态" },
      submit: async (values) => {
        await readEditableEvent();
        if (seq !== actionSeq || !isOwnerPage()) throw new Error("页面已切换，请关闭弹窗后重新操作");
        await api(ENDPOINT + "/" + encodeURIComponent(id) + "/status", {
          method: "POST", body: { status: values.status, remark: values.remark },
        });
      },
    });
    if (result === null) return;
    UI.toast("状态备注已保存");
    await loadFailureEvents();
    if (detailId === id && isOwnerPage()) await showDetail(id);
  } catch (error) {
    if (seq === actionSeq && isOwnerPage()) reportError(error);
  } finally {
    actionBusy = false;
    if (trigger.isConnected) trigger.disabled = locked;
  }
}

export function bindFailureEvents() {
  if (bound || getRole() !== "owner") return;
  bound = true;
  $("failure-filter")?.addEventListener("submit", (event) => {
    event.preventDefault();
    filters = {};
    for (const [key, id] of [["failureType", "failure-type"], ["status", "failure-status"], ["userId", "failure-user"], ["eventId", "failure-event"]]) {
      const value = $(id).value.trim();
      if (value) filters[key] = value;
    }
    hideDetail();
    loadFailureEvents(1);
  });
  $("failure-refresh")?.addEventListener("click", async () => {
    const id = detailId;
    await loadFailureEvents();
    if (id && detailId === id && isOwnerPage()) await showDetail(id);
  });
  $("failure-detail-close")?.addEventListener("click", () => hideDetail(true));
  const detailDialog = $("failure-detail");
  detailDialog?.addEventListener("cancel", (event) => {
    event.preventDefault();
    hideDetail(true);
  });
  detailDialog?.addEventListener("click", (event) => {
    if (event.target !== detailDialog) return;
    const bounds = detailDialog.getBoundingClientRect();
    if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) hideDetail(true);
  });
  $("failure-tbody")?.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action]");
    if (!button || !isOwnerPage()) return;
    if (button.dataset.action === "detail") showDetail(button.dataset.id, button);
    if (button.dataset.action === "status") markStatus(button.dataset.id, button);
  });
  const section = document.querySelector('.admin-page[data-page="failure-events"]');
  let wasActive = false;
  const onPageChange = () => {
    const active = isOwnerPage();
    if (active === wasActive) return;
    wasActive = active;
    if (active) return;
    ++listSeq;
    ++actionSeq;
    pendingLoad = null;
    hideDetail();
  };
  window.addEventListener("hashchange", onPageChange);
  if (section) new MutationObserver(onPageChange).observe(section, { attributes: true, attributeFilter: ["class"] });
}
