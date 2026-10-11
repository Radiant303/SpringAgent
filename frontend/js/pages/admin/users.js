/**
 * 用户管理业务逻辑
 */

import { fmtPoints, fmtBytes, fmtTime, parseNonNegative } from "../../utils/format.js";
import { getRole, ROLE_LABELS } from "../../api/auth.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";

export let users = [];
let usersLoaded = false;
let userOptionsListeners = [];

export function onUsersUpdated(cb) {
  userOptionsListeners.push(cb);
}

export async function ensureUsers() {
  if (!usersLoaded) await loadUsers();
}

export async function loadUsers() {
  users = await UI.loadTable("user-tbody", () => api("/api/admin/users")) || [];
  usersLoaded = true;
  renderUsers();
  userOptionsListeners.forEach((fn) => fn(users));
  return users;
}

export function renderUsers() {
  UI.closePopover();
  const myRole = getRole();
  UI.renderRows("user-tbody", users, (tr, u) => {
    const isOwnerRow = u.role === "owner";
    const operable = myRole === "owner" || (myRole === "admin" && u.role === "user");
    const disabled = u.status === "disabled";

    const actions = [];
    if (operable) {
      if (!isOwnerRow) {
        actions.push(`<button class="admin-btn ${disabled ? "" : "danger"}" data-act="status" data-id="${u.user_id}" data-name="${u.username}" data-next="${disabled ? "active" : "disabled"}">${disabled ? "启用" : "禁用"}</button>`);
      }
      actions.push(`<button class="admin-btn" data-act="password" data-id="${u.user_id}" data-name="${u.username}">重置密码</button>`);
      actions.push(`<button class="admin-btn more-btn" data-act="more" data-id="${u.user_id}" aria-label="更多操作" aria-expanded="false" title="更多"><svg class="more-icon" viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true"><circle cx="5" cy="12" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="19" cy="12" r="1.8"/></svg></button>`);
    }

    tr.className = disabled ? "disabled" : "";
    tr.innerHTML = `
      <td title="ID: ${u.user_id}"></td>
      <td class="user-email"></td>
      <td><span class="role-badge ${u.role}">${ROLE_LABELS[u.role] || u.role}</span></td>
      <td class="${disabled ? "status-disabled" : "status-active"}">${disabled ? "已禁用" : "正常"}</td>
      <td>${fmtBytes(u.quota_bytes)}</td>
      <td>${u.work_quota_bytes == null ? '<span class="quota-inherit">跟随全局</span>' : fmtBytes(u.work_quota_bytes)}</td>
      <td class="num">${fmtPoints(u.points_balance)}</td>
      <td>${fmtTime(u.created_at)}</td>
      <td><div class="row-actions">${actions.join("")}</div></td>`;
    tr.cells[0].textContent = u.username;
    tr.cells[1].textContent = u.email || "-";
  }, "暂无用户");
}

export async function onUserAction(btn) {
  const id = btn.dataset.id;
  const act = btn.dataset.act;
  const name = btn.dataset.name || "";
  const post = (path, body) => api(`/api/admin/users/${id}/${path}`, { method: "POST", body });
  const MB = 1 << 20;

  try {
    let done;
    if (act === "more") {
      if (btn.getAttribute("aria-expanded") === "true") {
        UI.closePopover();
        return;
      }
      const myRole = getRole();
      const u = users.find((x) => String(x.user_id) === String(id));
      if (!u) return;

      const items = [
        { act: "quota", label: "设配额", quota: u.quota_bytes },
        { act: "workquota", label: "work配额", quota: u.work_quota_bytes == null ? "" : u.work_quota_bytes },
      ];
      if (myRole === "owner" && u.role !== "owner") {
        items.push({ act: "points", label: "调账" });
        const toAdmin = u.role !== "admin";
        items.push({ act: "role", label: toAdmin ? "设为管理员" : "取消管理员", next: toAdmin ? "admin" : "user" });
      }

      const menu = document.createElement("div");
      menu.className = "admin-action-menu";
      items.forEach((it) => {
        const itemBtn = document.createElement("button");
        itemBtn.type = "button";
        itemBtn.className = "admin-action-item";
        itemBtn.textContent = it.label;
        itemBtn.dataset.act = it.act;
        itemBtn.dataset.id = u.user_id;
        itemBtn.dataset.name = u.username;
        if (it.quota !== undefined) itemBtn.dataset.quota = it.quota;
        if (it.next !== undefined) itemBtn.dataset.next = it.next;
        itemBtn.onclick = () => {
          UI.closePopover();
          onUserAction(itemBtn);
        };
        menu.appendChild(itemBtn);
      });

      btn.setAttribute("aria-expanded", "true");
      btn.classList.add("active");
      UI.openPopover(btn, menu, () => {
        btn.setAttribute("aria-expanded", "false");
        btn.classList.remove("active");
      }, { align: "center", minWidth: 96 });
      return;
    } else if (act === "status") {
      const next = btn.dataset.next;
      if (next === "disabled") {
        const ok = await UI.confirm({
          title: "禁用账号",
          message: `确定禁用「${name}」吗？\n禁用后该账号立即无法登录与使用。`,
          okText: "禁用",
          danger: true,
          submit: () => post("status", { status: next }),
        });
        if (!ok) return;
        done = `已禁用「${name}」`;
      } else {
        await post("status", { status: next });
        done = `已启用「${name}」`;
      }
    } else if (act === "role") {
      const next = btn.dataset.next;
      await post("role", { role: next });
      done = next === "admin" ? "已设为管理员" : "已取消管理员";
    } else if (act === "password") {
      const v = await UI.dialog({
        title: "重置密码",
        message: `为「${name}」设置一个新的登录密码。`,
        fields: [
          { name: "pwd", label: "新密码", type: "password", autocomplete: "new-password", hint: "至少 6 位" },
          { name: "pwd2", label: "确认新密码", type: "password", autocomplete: "new-password" },
        ],
        okText: "重置密码",
        validate: (f) => {
          if (f.pwd.length < 6) return { name: "pwd", message: "密码至少 6 位" };
          if (f.pwd !== f.pwd2) return { name: "pwd2", message: "两次输入的密码不一致" };
          return null;
        },
        submit: (f) => post("password", { password: f.pwd }),
      });
      if (!v) return;
      done = "密码已重置";
    } else if (act === "quota") {
      const currentMb = Math.round(Number(btn.dataset.quota || 0) / MB);
      const v = await UI.prompt({
        title: "存储配额",
        message: `设置「${name}」可使用的总存储空间。`,
        label: "配额",
        value: currentMb,
        inputMode: "decimal",
        suffix: "MB",
        okText: "保存",
        validate: (s) => (parseNonNegative(s) == null ? "请输入非负数字" : null),
        submit: (s) => post("quota", { quotaBytes: Math.round(parseNonNegative(s) * MB) }),
      });
      if (v === null) return;
      done = "存储配额已更新";
    } else if (act === "workquota") {
      const current = btn.dataset.quota === "" ? "" : String(Math.round(Number(btn.dataset.quota) / MB));
      const v = await UI.prompt({
        title: "单工作区配额",
        message: `设置「${name}」每个项目目录允许占用的最大空间。`,
        label: "配额",
        value: current,
        placeholder: "跟随全局",
        inputMode: "decimal",
        suffix: "MB",
        hint: "留空则恢复跟随全局设置",
        okText: "保存",
        validate: (s) => (s.trim() === "" || parseNonNegative(s) != null ? null : "请输入非负数字，或留空"),
        submit: (s) => post("work-quota", {
          quotaBytes: s.trim() === "" ? null : Math.round(parseNonNegative(s) * MB),
        }),
      });
      if (v === null) return;
      done = v.trim() === "" ? "已恢复跟随全局" : "单工作区配额已更新";
    } else if (act === "points") {
      const v = await UI.dialog({
        title: "调账",
        message: `调整「${name}」的积分余额，记入积分流水备查。`,
        fields: [
          { name: "delta", label: "变动数额", inputMode: "decimal", placeholder: "例如 100 或 -50", hint: "正数为充值，负数为扣减" },
          { name: "reason", label: "事由", placeholder: "必填，记账备查" },
        ],
        okText: "确认调账",
        validate: (f) => {
          const delta = Number(f.delta.trim());
          if (f.delta.trim() === "" || !Number.isFinite(delta) || delta === 0) return { name: "delta", message: "请输入非零数字" };
          if (!f.reason.trim()) return { name: "reason", message: "事由不能为空" };
          return null;
        },
        submit: (f) => post("points", { delta: Number(f.delta.trim()), reason: f.reason.trim() }),
      });
      if (!v) return;
      done = "调账完成";
    }
    if (done) UI.toast(done);
    await loadUsers();
  } catch (e) {
    UI.toast(e.message, { type: "error" });
  }
}
