/**
 * 搜索计费失败事件管理。
 *
 * @author hanbing
 * @since 2026-10-18
 */

import { $, h } from "../../utils/dom.js";
import { fmtPoints, fmtTime } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { getRole } from "../../api/auth.js";
import { UI } from "../../components/ui.js";

const ENDPOINT = "/api/admin/failure-events";
const STATUS_LABELS = {
  PENDING: "待处理",
  RETRYING: "自动重试中",
  SUCCEEDED: "重试成功",
  RESOLVED: "已处理",
  IGNORED: "已忽略",
};
const MANUAL_STATUSES = ["PENDING", "RESOLVED", "IGNORED"];
const MANAGEMENT_NOTICE = "修改状态仅作管理标记，不会扣积分，也不会复位自动重试次数。自动重试中不可人工修改；参考积分不代表实际扣款。";
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
const isOwnerPage = () => getRole() === "owner" && document.querySelector('.admin-page[data-page="failure-events"]')?.classList.contains("active");
const reportError = (error) => UI.toast(error?.message || "请求失败，请重试", { type: "error" });

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
  UI.renderRows("failure-tbody", data.list, (tr, event) => {
    tr.replaceChildren();
    cell(tr, time(event.createdAt), "failure-time");
    cell(tr, event.userId, "failure-user");
    cell(tr, event.eventId, "failure-event");
    cell(tr, points(event.costPoints), "num failure-points");
    cell(tr, statusLabel(event.status), "failure-status-cell");
    const actions = h("td", "failure-actions");
    for (const [action, label] of [["detail", "详情"], ["status", "标记状态"]]) {
      const button = h("button", "admin-btn", label);
      button.type = "button";
      button.dataset.action = action;
      button.dataset.id = String(event.id);
      if (action === "status" && ["RETRYING", "SUCCEEDED"].includes(event.status)) {
        button.disabled = true;
        button.title = event.status === "SUCCEEDED" ? "已计费成功，不可人工修改" : "自动重试中不可人工修改";
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

function renderDetail(event) {
  const root = $("failure-detail-body");
  root.replaceChildren(h("p", "settings-hint", MANAGEMENT_NOTICE));
  const fields = [
    ["记录 ID", event.id], ["事件 ID", event.eventId],
    ["Provider", event.provider], ["用户 ID", event.userId],
    ["会话 ID", event.sessionId], ["轮次 ID", event.turnId],
    ["搜索次数", event.searches], ["每次参考积分", points(event.unitPoints)],
    ["参考总积分（非实扣）", points(event.costPoints)], ["状态", statusLabel(event.status)],
    ["重试次数 / 上限", display(event.retryCount) + " / " + display(event.maxRetries)],
    ["下次自动重试", time(event.nextRetryAt)],
    ["创建时间", time(event.createdAt)], ["更新时间", time(event.updatedAt)],
    ["处理人 ID", event.handledBy], ["处理时间", time(event.handledAt)],
  ];
  const metadata = h("dl", "failure-meta");
  for (const [label, value] of fields) {
    const row = h("div");
    row.append(h("dt", null, label), h("dd", null, display(value)));
    metadata.appendChild(row);
  }
  root.appendChild(metadata);
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
  let retrying = false;
  try {
    const event = await api(ENDPOINT + "/" + encodeURIComponent(id));
    if (seq !== actionSeq || !isOwnerPage()) return;
    if (!event) throw new Error("未找到失败事件");
    if (event.status === "RETRYING") {
      retrying = true;
      await loadFailureEvents();
      if (detailId === id) await showDetail(id);
      throw new Error("事件正在自动重试，请稍后刷新再操作");
    }
    const options = MANUAL_STATUSES.map((value) => ({ value, label: STATUS_LABELS[value] }));
    if (!MANUAL_STATUSES.includes(event.status)) options.unshift({ value: "", label: "请选择管理状态" });
    const result = await UI.dialog({
      title: "标记失败事件状态",
      message: "事件：" + display(event.eventId) + "\n当前状态：" + statusLabel(event.status) + "\n" + MANAGEMENT_NOTICE,
      okText: "保存标记",
      fields: [
        { name: "status", label: "管理状态", type: "select", value: MANUAL_STATUSES.includes(event.status) ? event.status : "", options },
        { name: "remark", label: "管理备注", type: "textarea", value: event.remark || "", rows: 5, placeholder: "填写处理说明或忽略原因" },
      ],
      validate: (values) => MANUAL_STATUSES.includes(values.status) ? null : { name: "status", message: "请选择有效的管理状态" },
      submit: async (values) => {
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
    if (trigger.isConnected) trigger.disabled = retrying;
  }
}

export function bindFailureEvents() {
  if (bound || getRole() !== "owner") return;
  bound = true;
  $("failure-filter")?.addEventListener("submit", (event) => {
    event.preventDefault();
    filters = {};
    for (const [key, id] of [["status", "failure-status"], ["userId", "failure-user"], ["eventId", "failure-event"]]) {
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
