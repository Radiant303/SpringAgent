/**
 * 账号与安全设置（资料查看、改密、换绑邮箱）
 */

import { $ } from "../../utils/dom.js";
import { fmtPoints } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { goLogin } from "../../api/auth.js";
import { UI } from "../../components/ui.js";

let acEmail = "";
let acMailOn = false;

function acCountdown(btn, seconds) {
  let left = seconds;
  btn.disabled = true;
  btn.textContent = left + "s 后重发";
  const timer = setInterval(() => {
    left -= 1;
    if (left <= 0) {
      clearInterval(timer);
      btn.disabled = false;
      btn.textContent = "获取验证码";
    } else {
      btn.textContent = left + "s 后重发";
    }
  }, 1000);
}

async function acSendCode(btn) {
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = "发送中…";
  try {
    const data = await api("/api/account/send-code", { method: "POST" });
    acCountdown(btn, (data && data.resendAfterSeconds) || 60);
    UI.toast("验证码已发送，请查收邮件");
  } catch (err) {
    btn.disabled = false;
    btn.textContent = "获取验证码";
    UI.toast(err.message, { type: "error" });
  }
}

async function acSendBindCode(btn, email) {
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = "发送中…";
  try {
    const data = await api("/api/account/send-bind-code", { method: "POST", body: { email } });
    acCountdown(btn, (data && data.resendAfterSeconds) || 60);
    UI.toast("验证码已发送，请查收邮件");
  } catch (err) {
    btn.disabled = false;
    btn.textContent = "获取验证码";
    UI.toast(err.message, { type: "error" });
  }
}

export function openChangePassword() {
  if (acMailOn && !acEmail) {
    UI.toast("请先绑定邮箱后再修改密码", { type: "error" });
    return;
  }
  if (acMailOn) {
    UI.dialog({
      title: "修改密码",
      message: `验证码将发送至绑定邮箱 ${acEmail}`,
      fields: [
        {
          name: "code", label: "邮箱验证码", placeholder: "6 位数字", inputMode: "numeric", maxLength: 6,
          suffixBtn: { text: "获取验证码", onClick: (btn) => acSendCode(btn) },
        },
        { name: "newPassword", label: "新密码", type: "password", placeholder: "至少 6 位", autocomplete: "new-password" },
        { name: "newPassword2", label: "确认新密码", type: "password", placeholder: "再次输入新密码", autocomplete: "new-password" },
      ],
      okText: "确认修改",
      validate: (v) => {
        if (!/^\d{6}$/.test(v.code.trim())) return { message: "请输入 6 位数字验证码", name: "code" };
        if (v.newPassword.length < 6) return { message: "密码至少 6 位", name: "newPassword" };
        if (v.newPassword !== v.newPassword2) return { message: "两次输入的密码不一致", name: "newPassword2" };
        return null;
      },
      submit: (v) => api("/api/account/change-password", {
        method: "POST",
        body: { code: v.code.trim(), newPassword: v.newPassword },
      }),
    }).then((ok) => { if (ok) goLogin("passwordChanged"); });
    return;
  }
  UI.dialog({
    title: "修改密码",
    fields: [
      { name: "oldPassword", label: "旧密码", type: "password", placeholder: "请输入当前密码", autocomplete: "current-password" },
      { name: "newPassword", label: "新密码", type: "password", placeholder: "至少 6 位", autocomplete: "new-password" },
      { name: "newPassword2", label: "确认新密码", type: "password", placeholder: "再次输入新密码", autocomplete: "new-password" },
    ],
    okText: "确认修改",
    validate: (v) => {
      if (!v.oldPassword) return { message: "请输入旧密码", name: "oldPassword" };
      if (v.newPassword.length < 6) return { message: "密码至少 6 位", name: "newPassword" };
      if (v.newPassword !== v.newPassword2) return { message: "两次输入的密码不一致", name: "newPassword2" };
      if (v.oldPassword === v.newPassword) return { message: "新密码不能与旧密码相同", name: "newPassword" };
      return null;
    },
    submit: (v) => api("/api/account/change-password", {
      method: "POST",
      body: { oldPassword: v.oldPassword, newPassword: v.newPassword },
    }),
  }).then((ok) => { if (ok) goLogin("passwordChanged"); });
}

export function openBindEmailDialog(isChange) {
  UI.dialog({
    title: isChange ? "修改绑定邮箱" : "绑定邮箱",
    message: isChange ? `当前绑定邮箱：${acEmail}；验证码将发送至新邮箱` : "绑定后可用邮箱验证码重置密码",
    fields: [
      { name: "email", label: isChange ? "新邮箱" : "QQ 邮箱", type: "email", placeholder: "用于接收验证码", autocomplete: "email" },
      {
        name: "code", label: "邮箱验证码", placeholder: "6 位数字", inputMode: "numeric", maxLength: 6,
        suffixBtn: {
          text: "获取验证码",
          onClick: (btn, _input, inputs) => {
            const email = inputs.email.value.trim();
            if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
              UI.toast("请输入正确的邮箱地址", { type: "error" });
              inputs.email.focus();
              return;
            }
            acSendBindCode(btn, email);
          },
        },
      },
    ],
    okText: isChange ? "确认修改" : "绑定",
    validate: (v) => {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v.email.trim())) return { message: "请输入正确的邮箱地址", name: "email" };
      if (!/^\d{6}$/.test(v.code.trim())) return { message: "请输入 6 位数字验证码", name: "code" };
      return null;
    },
    submit: async (v) => {
      await api("/api/account/bind-email", {
        method: "POST",
        body: { email: v.email.trim(), code: v.code.trim() },
      });
    },
  }).then((ok) => {
    if (ok) {
      UI.toast(isChange ? "绑定邮箱已修改" : "邮箱绑定成功");
      loadAccount();
    }
  });
}

export function onBindEmail() {
  openBindEmailDialog(!!acEmail);
}

export async function loadAccount() {
  const me = await api("/api/billing/me");
  const balance = Number(me?.balance ?? 0);
  const el = $("account-points");
  if (el) {
    el.textContent = fmtPoints(balance);
    el.classList.toggle("points-out", balance < 0);
  }

  try {
    const profile = await api("/api/account/me");
    acEmail = profile?.email || "";
    if ($("account-email")) $("account-email").textContent = acEmail;
    const cfg = await fetch("/api/auth/public-config").then((r) => r.json()).catch(() => null);
    acMailOn = !!(cfg && cfg.data && cfg.data.mailVerify);
    if ($("account-email-row")) $("account-email-row").classList.toggle("hidden", !acMailOn);
    if ($("ac-bind-email")) $("ac-bind-email").textContent = acEmail ? "修改" : "绑定";
  } catch {
    if ($("account-email")) $("account-email").textContent = "-";
  }
}
