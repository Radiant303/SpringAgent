/**
 * 兑换码管理业务逻辑
 */

import { $ } from "../../utils/dom.js";
import { fmtPoints, fmtBytes, fmtDT } from "../../utils/format.js";
import { parseInteger } from "../../utils/numeric.js";
import { loadResourceLimits, pointsValue, quotaValue, pointsHint, quotaHint } from "../../utils/resourceLimits.js";
import { getRole } from "../../api/auth.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { users } from "./users.js";

/* 状态用着色文字表达（§6.9），不用底色徽章 */
export function redeemStatusHtml(row) {
  if (row.status === "REDEEMED") return `<span class="face-zero">已使用</span>`;
  if (row.status === "REVOKED") return `<span class="status-disabled">已作废</span>`;
  if (row.expired) return `<span class="face-zero">已过期</span>`;
  return `<span class="status-active">待使用</span>`;
}

/* 面值单元格：0 显示为淡色占位，数值与类型分列 */
export function faceCell(value, fmt) {
  const n = Number(value);
  return n > 0 ? fmt(n) : `<span class="face-zero">–</span>`;
}

let page = 1;
let size = 10;

export async function loadRedeemCodes(p = page) {
  const params = new URLSearchParams();
  if ($("redeem-status") && $("redeem-status").value) {
    params.set("status", $("redeem-status").value);
  }
  params.set("page", p);
  params.set("size", size);
  const data = await UI.loadTable("redeem-tbody", () => api("/api/admin/redeem-codes?" + params.toString()));
  if (data && !data.list.length && p > 1) return loadRedeemCodes(p - 1);
  page = p;
  UI.renderPager("redeem-pager", { page, size, total: data?.total || 0 }, {
    onPage: (np) => loadRedeemCodes(np).catch((e) => UI.toast(e.message, { type: "error" })),
    onSize: (ns) => { size = ns; loadRedeemCodes(1).catch((e) => UI.toast(e.message, { type: "error" })); },
  });
  const rows = data?.list || [];
  const nameOf = Object.fromEntries(users.map((u) => [u.user_id, u.username]));
  const myRole = getRole();
  UI.renderRows("redeem-tbody", rows, (tr, row) => {
    const isUsable = row.status === "ACTIVE" && !row.expired;
    const actions = [`<button class="admin-btn" data-act="copy" data-code="${row.code}">复制</button>`];
    if (myRole === "owner" && isUsable) {
      actions.push(`<button class="admin-btn danger" data-act="revoke" data-id="${row.id}">作废</button>`);
    }
    const redeemedBy = row.redeemed_by ? (nameOf[row.redeemed_by] || row.redeemed_by) : null;
    tr.className = isUsable ? "" : "disabled";
    tr.innerHTML = `
      <td><code class="redeem-code">${row.code}</code></td>
      <td class="num">${faceCell(row.points, fmtPoints)}</td>
      <td class="num">${faceCell(row.storage_delta_bytes, fmtBytes)}</td>
      <td class="num">${faceCell(row.work_quota_delta_bytes, fmtBytes)}</td>
      <td>${redeemStatusHtml(row)}</td>
      <td class="time-cell">${row.expires_at ? fmtDT(row.expires_at) : '<span class="face-zero">永久</span>'}</td>
      <td></td>
      <td class="time-cell">${fmtDT(row.created_at)}</td>
      <td><div class="row-actions">${actions.join("")}</div></td>`;
    tr.cells[6].innerHTML = redeemedBy
      ? `${redeemedBy}<div class="cell-sub">${fmtDT(row.redeemed_at)}</div>`
      : `<span class="face-zero">–</span>`;
  }, "暂无兑换码");
}

/* 生成兑换码：右上角主按钮弹出的对话框；校验/提交都在框内，失败可改了重试 */
export async function openRedeemGen() {
  const limits = await loadResourceLimits();
  const parseCount = (s) => parseInteger(s, { min: 1, max: limits.redeemCountMax });
  const parseHours = (s) => parseInteger(s, { min: 1, max: limits.expiresHoursMax });
  const parsePoints = (s) => pointsValue(s, limits, { empty: "0" });
  const parseQuota = (s) => quotaValue(s, limits, { empty: 0 });
  const v = await UI.dialog({
    title: "生成兑换码",
    message: `兑换后积分余额不超过 ${limits.balanceMax}，配额不超过 1 TB；超限时兑换码仍可用。`,
    okText: "生成",
    fields: [
      { name: "count", label: "数量", value: "1", inputMode: "numeric", maxLength: 3 },
      { name: "points", label: "积分", placeholder: "0", inputMode: "decimal", maxLength: 14 },
      { name: "storageMb", label: "存储配额", suffix: "MB", placeholder: "0", inputMode: "numeric", maxLength: 7 },
      { name: "workQuotaMb", label: "单工作区配额", suffix: "MB", placeholder: "0", inputMode: "numeric", maxLength: 7 },
      { name: "expires", label: "有效期", suffix: "小时", placeholder: "永久", inputMode: "numeric", maxLength: 5 },
    ],
    validate: (f) => {
      if (parseCount(f.count) === null) return { name: "count", message: `数量须为 1～${limits.redeemCountMax} 的整数` };
      const points = parsePoints(f.points);
      const storageMb = parseQuota(f.storageMb);
      const workQuotaMb = parseQuota(f.workQuotaMb);
      if (points === null) return { name: "points", message: pointsHint(limits) };
      if (storageMb === null) return { name: "storageMb", message: quotaHint(limits) };
      if (workQuotaMb === null) return { name: "workQuotaMb", message: quotaHint(limits) };
      if (String(f.expires).trim() !== "" && parseHours(f.expires) === null) {
        return { name: "expires", message: `有效期须为 1～${limits.expiresHoursMax} 小时的整数，或留空` };
      }
      if (Number(points) === 0 && storageMb === 0 && workQuotaMb === 0) {
        return { name: "points", message: "三项面值至少一项大于 0" };
      }
      return null;
    },
    submit: (f) => {
      const body = {
        count: parseCount(f.count),
        points: parsePoints(f.points),
        storage_mb: parseQuota(f.storageMb),
        work_quota_mb: parseQuota(f.workQuotaMb),
      };
      if (String(f.expires).trim() !== "") body.expires_in_hours = parseHours(f.expires);
      return api("/api/admin/redeem-codes", { method: "POST", body });
    },
  });
  if (v === null) return;
  UI.toast(`已生成 ${v.count} 个兑换码`);
  await loadRedeemCodes();
}

export async function onRedeemAction(btn) {
  try {
    if (btn.dataset.act === "copy") {
      await navigator.clipboard.writeText(btn.dataset.code);
      UI.toast("兑换码已复制");
    } else if (btn.dataset.act === "revoke") {
      const ok = await UI.confirm({
        title: "作废兑换码",
        message: "确定作废该兑换码吗？作废后无法兑换。",
        okText: "作废",
        danger: true,
        submit: () => api(`/api/admin/redeem-codes/${btn.dataset.id}/revoke`, { method: "POST" }),
      });
      if (!ok) return;
      UI.toast("兑换码已作废");
      await loadRedeemCodes();
    }
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}
