/**
 * 用量统计查询与报表
 */

import { $ } from "../../utils/dom.js";
import { fmtNum } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { users } from "./users.js";

export function renderUsageUserOptions(list = users) {
  const sel = $("usage-user");
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

let page = 1;
let size = 10;

export async function loadUsage(p = page) {
  if (users && users.length) renderUsageUserOptions(users);
  const params = new URLSearchParams();
  if ($("usage-user")?.value) params.set("userId", $("usage-user").value);
  if ($("usage-from")?.value) params.set("from", $("usage-from").value);
  if ($("usage-to")?.value) {
    const end = new Date($("usage-to").value + "T00:00:00");
    end.setDate(end.getDate() + 1);
    params.set("to", end.toISOString().slice(0, 10));
  }
  params.set("page", p);
  params.set("size", size);
  const data = await UI.loadTable("usage-tbody", () => api("/api/admin/usage?" + params.toString()));
  if (data && !data.list.length && p > 1) return loadUsage(p - 1);
  page = p;
  UI.renderPager("usage-pager", { page, size, total: data?.total || 0 }, {
    onPage: (np) => loadUsage(np).catch((e) => UI.toast(e.message, { type: "error" })),
    onSize: (ns) => { size = ns; loadUsage(1).catch((e) => UI.toast(e.message, { type: "error" })); },
  });
  UI.renderRows("usage-tbody", data?.list || [], (tr, r) => {
    tr.className = "";
    tr.innerHTML = `
      <td></td>
      <td></td>
      <td class="num">${fmtNum(r.turns)}</td>
      <td class="num">${fmtNum(r.requests)}</td>
      <td class="num">${fmtNum(r.inputTokens)}</td>
      <td class="num">${fmtNum(r.cacheReadTokens)}</td>
      <td class="num">${fmtNum(r.cacheWriteTokens)}</td>
      <td class="num">${fmtNum(r.outputTokens)}</td>`;
    tr.cells[0].textContent = r.username || r.userId;
    tr.cells[1].textContent = r.modelId || "-";
    tr.cells[1].title = r.modelId || "";
  }, "暂无用量记录");
}
