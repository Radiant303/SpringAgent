/**
 * 逐次模型请求使用日志
 */

import { $ } from "../../utils/dom.js";
import { fmtNum, fmtPoints, fmtTime } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { users } from "./users.js";

const STATUS_LABELS = {
  finished: "完成",
  cancelled: "已取消",
  error: "出错",
};

const AGENT_LABELS = {
  main: "主代理",
  searcher: "检索",
  reviewer: "审查",
  compaction: "压缩",
  legacy: "历史",
};

/**
 * 管理端用户筛选下拉框
 */
export function renderUsageRecordsUserOptions(list = users) {
  const sel = $("usage-records-user");
  if (!sel) return;
  const current = sel.value;
  sel.innerHTML = `<option value="">全部用户</option>`;
  for (const u of list) {
    const opt = document.createElement("option");
    opt.value = u.user_id;
    opt.textContent = u.username;
    sel.appendChild(opt);
  }
  sel.value = current;
}

function dateParam(value, nextDay = false) {
  if (!value) return null;
  const d = new Date(value + "T00:00:00");
  if (nextDay) d.setDate(d.getDate() + 1);
  return d.toISOString().slice(0, 19).replace("T", " ");
}

function renderRecords(tbodyId, list, withUser) {
  const nameOf = Object.fromEntries((users || []).map((u) => [u.user_id, u.username]));
  UI.renderRows(tbodyId, list, (tr, r) => {
    const cols = withUser
      ? ["", "", "", "", "", "num", "num", "num", "num", "num", ""]
      : ["", "", "", "num", "num", "num", "num", "num", ""];
    tr.className = "";
    tr.innerHTML = cols.map((cls) => `<td${cls ? ` class="${cls}"` : ""}></td>`).join("");
    let i = 0;
    const time = tr.cells[i++];
    time.textContent = fmtTime(r.createdAt);
    time.title = [r.requestId, r.turnId].filter(Boolean).join("\n");
    if (withUser) tr.cells[i++].textContent = nameOf[r.userId] || r.userId;
    const model = tr.cells[i++];
    model.textContent = r.modelName || "-";
    model.title = r.modelName || "";
    if (withUser) tr.cells[i++].textContent = r.modelId || "-";
    tr.cells[i++].textContent = AGENT_LABELS[r.agentName] || r.agentName || "-";
    tr.cells[i++].textContent = fmtNum(r.inputTokens);
    tr.cells[i++].textContent = fmtNum(r.cacheReadTokens);
    tr.cells[i++].textContent = fmtNum(r.cacheWriteTokens);
    tr.cells[i++].textContent = fmtNum(r.outputTokens);
    tr.cells[i++].textContent = r.costPoints == null ? "—" : fmtPoints(r.costPoints);
    const status = STATUS_LABELS[r.status] || r.status;
    tr.cells[i++].textContent = r.usageKnown ? status : `${status} · 用量未知`;
  }, "暂无使用记录");
}

let adminPage = 1;
let adminSize = 10;

export async function loadUsageRecords(p = adminPage) {
  if (users && users.length) renderUsageRecordsUserOptions(users);
  const params = new URLSearchParams();
  if ($("usage-records-user")?.value) params.set("userId", $("usage-records-user").value);
  const from = dateParam($("usage-records-from")?.value);
  const to = dateParam($("usage-records-to")?.value, true);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  params.set("page", p);
  params.set("size", adminSize);
  const data = await UI.loadTable("usage-records-tbody",
    () => api("/api/admin/usage/records?" + params.toString()));
  if (data && !data.list.length && p > 1) return loadUsageRecords(p - 1);
  adminPage = p;
  UI.renderPager("usage-records-pager", { page: p, size: adminSize, total: data?.total || 0 }, {
    onPage: (np) => loadUsageRecords(np).catch((e) => UI.toast(e.message, { type: "error" })),
    onSize: (ns) => {
      adminSize = ns;
      loadUsageRecords(1).catch((e) => UI.toast(e.message, { type: "error" }));
    },
  });
  renderRecords("usage-records-tbody", data?.list || [], true);
}

let myPage = 1;
let mySize = 10;

export async function loadMyUsage(p = myPage) {
  const params = new URLSearchParams();
  const from = dateParam($("my-usage-from")?.value);
  const to = dateParam($("my-usage-to")?.value, true);
  if (from) params.set("from", from);
  if (to) params.set("to", to);
  params.set("page", p);
  params.set("size", mySize);
  const data = await UI.loadTable("my-usage-tbody",
    () => api("/api/billing/usage?" + params.toString()));
  if (data && !data.list.length && p > 1) return loadMyUsage(p - 1);
  myPage = p;
  UI.renderPager("my-usage-pager", { page: p, size: mySize, total: data?.total || 0 }, {
    onPage: (np) => loadMyUsage(np).catch((e) => UI.toast(e.message, { type: "error" })),
    onSize: (ns) => {
      mySize = ns;
      loadMyUsage(1).catch((e) => UI.toast(e.message, { type: "error" }));
    },
  });
  renderRecords("my-usage-tbody", data?.list || [], false);
}
