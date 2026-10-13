/**
 * 个人账单、配额与积分兑换
 */

import { $ } from "../../utils/dom.js";
import { fmtPoints, fmtBytes, fmtDT } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";

export const LEDGER_TYPE_LABELS = {
  HOLD: "预扣",
  SETTLE: "结算",
  RELEASE: "释放",
  DIRECT: "直扣",
  ADJUST: "调账",
  REDEEM: "兑换",
};

export function grantedPartsText(g) {
  const parts = [];
  if (Number(g.granted_points) > 0) parts.push("+" + fmtPoints(g.granted_points) + " 积分");
  if (Number(g.granted_storage_bytes) > 0) parts.push("+" + fmtBytes(g.granted_storage_bytes) + " 存储配额");
  if (Number(g.granted_work_quota_bytes) > 0) parts.push("+" + fmtBytes(g.granted_work_quota_bytes) + " work 配额");
  return parts.join(" · ");
}

export async function loadMyPoints() {
  const me = await api("/api/billing/me");
  const balance = Number(me?.balance ?? 0);
  const big = $("points-big");
  if (big) {
    big.textContent = fmtPoints(balance);
    big.classList.toggle("negative", balance < 0);
  }
  if ($("quota-storage")) {
    $("quota-storage").textContent = me
      ? fmtBytes(me.storage_used_bytes) + " / " + fmtBytes(me.quota_bytes)
      : "-";
  }
  if ($("quota-work")) {
    $("quota-work").textContent = me ? fmtBytes(me.work_quota_bytes) : "-";
  }

  const reds = await api("/api/billing/redemptions");
  const redBox = $("my-redemptions");
  if (redBox) {
    redBox.innerHTML = "";
    if (!reds || !reds.length) {
      redBox.innerHTML = `<div class="empty-tip">暂无兑换记录</div>`;
    } else {
      for (const r of reds) {
        const chips = [];
        if (Number(r.granted_points) > 0) chips.push(`<span class="red-chip">+${fmtPoints(r.granted_points)} 积分</span>`);
        if (Number(r.granted_storage_bytes) > 0) chips.push(`<span class="red-chip">+${fmtBytes(r.granted_storage_bytes)} 存储配额</span>`);
        if (Number(r.granted_work_quota_bytes) > 0) chips.push(`<span class="red-chip">+${fmtBytes(r.granted_work_quota_bytes)} work 配额</span>`);
        const div = document.createElement("div");
        div.className = "red-item";
        div.innerHTML = `
          <svg class="red-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="8" width="18" height="4" rx="1"/><path d="M5 12v8a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-8M12 8v13M12 8s-1.5-5-4.5-5a2 2 0 0 0 0 4M12 8s1.5-5 4.5-5a2 2 0 0 1 0 4"/></svg>
          <div class="red-main">
            <div class="red-chips">${chips.join("")}</div>
            <div class="red-meta">${fmtDT(r.redeemed_at)} · ${r.code}</div>
          </div>`;
        redBox.appendChild(div);
      }
    }
  }

  const box = $("my-ledger");
  if (box) {
    box.innerHTML = "";
    const rows = me?.ledger || [];
    if (!rows.length) {
      box.innerHTML = `<div class="empty-tip">暂无明细</div>`;
      return;
    }
    for (const r of rows) {
      const change = Number(r.change_amount);
      const meta = [fmtDT(r.created_at), r.model_id, r.reason].filter(Boolean).join(" · ");
      const div = document.createElement("div");
      div.className = "ledger-row";
      div.innerHTML = `
        <div>
          <div class="ledger-type">${LEDGER_TYPE_LABELS[r.type] || r.type}</div>
          <div class="ledger-meta"></div>
        </div>
        <div class="ledger-change num ${change >= 0 ? "points-in" : "points-out"}">${change >= 0 ? "+" : ""}${fmtPoints(r.change_amount)}</div>`;
      div.querySelector(".ledger-meta").textContent = meta;
      box.appendChild(div);
    }
  }
}

export async function onUserRedeem() {
  const input = $("user-redeem-input");
  const code = input?.value.trim();
  if (!code) {
    UI.toast("请输入兑换码", { type: "error" });
    return;
  }
  const btn = $("user-redeem-btn");
  if (btn) btn.disabled = true;
  try {
    const granted = await api("/api/billing/redeem", { method: "POST", body: { code } });
    UI.toast("兑换成功：" + grantedPartsText(granted));
    if (input) input.value = "";
    await loadMyPoints();
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  } finally {
    if (btn) btn.disabled = false;
  }
}
