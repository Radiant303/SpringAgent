/**
 * 系统设置（仅站长）业务逻辑
 */

import { $ } from "../../utils/dom.js";
import { getRole } from "../../api/auth.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";

export async function loadSettings() {
  if (getRole() !== "owner") return;
  // 数据返回前输入框呈骨架态（无文字、脉动、不可编辑），保存按钮禁用，避免先看到空值或默认值
  const section = document.querySelector('.admin-page[data-page="settings"]');
  section?.classList.add("is-loading");
  if ($("settings-save")) $("settings-save").disabled = true;
  let data;
  try {
    data = await api("/api/admin/settings");
  } finally {
    section?.classList.remove("is-loading");
    if ($("settings-save")) $("settings-save").disabled = false;
  }
  if ($("registration-open")) $("registration-open").checked = !data || data.registrationOpen !== false;
  if ($("work-max-mb")) {
    $("work-max-mb").value = data && data.workMaxBytes != null
      ? Math.round(Number(data.workMaxBytes) / (1 << 20))
      : 20;
  }
  if ($("est-cache-read")) $("est-cache-read").value = data?.billingEstCacheReadTokens ?? 90000;
  if ($("est-input")) $("est-input").value = data?.billingEstInputTokens ?? 10000;
  if ($("est-output")) $("est-output").value = data?.billingEstOutputTokens ?? 20000;
  if ($("mail-enabled")) $("mail-enabled").checked = !!data && data.mailRegisterEnabled === true;
  if ($("mail-username")) $("mail-username").value = data?.mailUsername ?? "";
  // 授权码只写不读：不回显原值，留空表示保持不变
  if ($("mail-auth-code")) {
    $("mail-auth-code").value = "";
    $("mail-auth-code").placeholder = data && data.mailAuthCodeConfigured ? "已配置，留空保持不变" : "未配置";
  }
  if ($("mail-resend-interval")) $("mail-resend-interval").value = data?.mailResendIntervalSeconds ?? 60;
  if ($("mail-code-ttl")) $("mail-code-ttl").value = data?.mailCodeTtlSeconds ?? 300;
}

export async function saveSettings() {
  const mb = Number($("work-max-mb")?.value);
  if (!Number.isFinite(mb) || mb < 1) {
    UI.toast("单工作区配额至少 1 MB", { type: "error" });
    return;
  }
  const estCacheRead = Number($("est-cache-read")?.value);
  const estInput = Number($("est-input")?.value);
  const estOutput = Number($("est-output")?.value);
  if (![estCacheRead, estInput, estOutput].every((v) => Number.isFinite(v) && v >= 0)) {
    UI.toast("预扣档位必须是非负数字", { type: "error" });
    return;
  }
  if ($("settings-save")) $("settings-save").disabled = true;
  try {
    await api("/api/admin/settings/registration", {
      method: "POST",
      body: { open: $("registration-open").checked },
    });
    await api("/api/admin/settings/work-max-bytes", {
      method: "POST",
      body: { bytes: Math.round(mb * (1 << 20)) },
    });
    await api("/api/admin/settings/billing-est", {
      method: "POST",
      body: { cacheReadTokens: Math.round(estCacheRead), inputTokens: Math.round(estInput), outputTokens: Math.round(estOutput) },
    });
    // 邮箱验证码注册：邮箱/授权码留空 = 保持不变（后端按生效值校验开启条件）
    const mailResend = Number($("mail-resend-interval").value);
    const mailTtl = Number($("mail-code-ttl").value);
    if (!Number.isFinite(mailResend) || mailResend < 10 || !Number.isFinite(mailTtl) || mailTtl < 60) {
      throw new Error("重发间隔至少 10 秒，验证码有效期至少 60 秒");
    }
    await api("/api/admin/settings/mail", {
      method: "POST",
      body: {
        enabled: $("mail-enabled").checked,
        username: $("mail-username").value.trim(),
        authCode: $("mail-auth-code").value.trim(),
        resendIntervalSeconds: Math.round(mailResend),
        codeTtlSeconds: Math.round(mailTtl),
      },
    });
    if ($("settings-tip")) $("settings-tip").textContent = "";
    UI.toast("设置已保存");
  } catch (e) {
    UI.toast(e.message, { type: "error" });
  } finally {
    if ($("settings-save")) $("settings-save").disabled = false;
  }
}
