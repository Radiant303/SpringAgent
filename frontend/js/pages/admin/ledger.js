/**
 * 全局积分流水审计
 */

import { $ } from "../../utils/dom.js";
import { fmtPoints, fmtTime } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { users } from "./users.js";
import { LEDGER_TYPE_LABELS } from "./points.js";

export function renderLedgerUserOptions(list = users) {
  const sel = $("ledger-user");
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

export async function loadLedger(p = page) {
  if (users && users.length) renderLedgerUserOptions(users);
  const params = new URLSearchParams();
  if ($("ledger-user")?.value) params.set("userId", $("ledger-user").value);
  params.set("page", p);
  params.set("size", size);
  const data = await UI.loadTable("ledger-tbody", () => api("/api/admin/points/ledger?" + params.toString()));
  if (data && !data.list.length && p > 1) return loadLedger(p - 1);
  page = p;
  UI.renderPager("ledger-pager", { page, size, total: data?.total || 0 }, {
    onPage: (np) => loadLedger(np).catch((e) => UI.toast(e.message, { type: "error" })),
    onSize: (ns) => { size = ns; loadLedger(1).catch((e) => UI.toast(e.message, { type: "error" })); },
  });
  const nameOf = Object.fromEntries(users.map((u) => [u.user_id, u.username]));
  UI.renderRows("ledger-tbody", data?.list || [], (tr, r) => {
    const change = Number(r.change_amount);
    tr.className = "";
    tr.innerHTML = `
      <td>${fmtTime(r.created_at)}</td>
      <td></td>
      <td>${LEDGER_TYPE_LABELS[r.type] || r.type}</td>
      <td class="num ${change >= 0 ? "points-in" : "points-out"}">${change >= 0 ? "+" : ""}${fmtPoints(r.change_amount)}</td>
      <td class="num">${fmtPoints(r.balance_after)}</td>
      <td>${r.model_name || "-"}</td>
      <td class="ref-cell" title="${r.ref_id || ""}">${r.ref_id || "-"}</td>
      <td>${r.reason || "-"}</td>`;
    tr.cells[1].textContent = nameOf[r.user_id] || r.user_id;
  }, "暂无流水");
}
