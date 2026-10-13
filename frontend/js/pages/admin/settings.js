/**
 * 系统设置（仅站长）业务逻辑
 */

import { $ } from "../../utils/dom.js";
import { parseInteger } from "../../utils/numeric.js";
import { loadResourceLimits, quotaValue } from "../../utils/resourceLimits.js";
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
  let limits;
  try {
    [data, limits] = await Promise.all([api("/api/admin/settings"), loadResourceLimits()]);
    if ($("work-max-mb")) {
      $("work-max-mb").max = limits.quotaMbMax;
      $("work-max-mb").step = "1";
      $("work-max-mb").title = `1～${limits.quotaMbMax} MB，整数`;
    }
    for (const id of ["est-cache-read", "est-input", "est-output"]) {
      if ($(id)) {
        $(id).max = limits.billingTokensMax;
        $(id).step = "1";
        $(id).title = `0～${limits.billingTokensMax} tokens，整数`;
      }
    }
  } finally {
    section?.classList.remove("is-loading");
    if ($("settings-save")) $("settings-save").disabled = false;
  }
  if ($("registration-open")) $("registration-open").checked = !data || data.registrationOpen !== false;
  if ($("work-max-mb")) {
    $("work-max-mb").value = data && data.workMaxBytes != null
      ? Number(data.workMaxBytes) / limits.mbBytes
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
  if ($("community-link")) $("community-link").value = data?.communityLink ?? "";
}

export async function saveSettings() {
  if ($("settings-save")?.disabled) return;
  if ($("settings-save")) $("settings-save").disabled = true;
  try {
    const limits = await loadResourceLimits();
    const mb = quotaValue($("work-max-mb")?.value, limits, { min: 1 });
    if (mb === null) throw new Error(`单工作区配额须为 1～${limits.quotaMbMax} MB 的整数`);
    const token = (id) => parseInteger($(id)?.value, { max: limits.billingTokensMax });
    const estCacheRead = token("est-cache-read");
    const estInput = token("est-input");
    const estOutput = token("est-output");
    if ([estCacheRead, estInput, estOutput].includes(null)) {
      throw new Error(`冻结预估须为 0～${limits.billingTokensMax} tokens 的整数`);
    }
    const communityLink = $("community-link").value.trim();
    if (communityLink && !/^https?:\/\//.test(communityLink)) {
      throw new Error("社群链接必须以 http:// 或 https:// 开头");
    }
    const mailResend = parseInteger($("mail-resend-interval").value, { min: 10, max: 3600 });
    const mailTtl = parseInteger($("mail-code-ttl").value, { min: 60, max: 3600 });
    if (mailResend === null || mailTtl === null) {
      throw new Error("重发间隔须为 10～3600 秒、验证码有效期须为 60～3600 秒的整数");
    }
    await api("/api/admin/settings/registration", {
      method: "POST",
      body: { open: $("registration-open").checked },
    });
    await api("/api/admin/settings/work-max-bytes", {
      method: "POST",
      body: { bytes: mb * limits.mbBytes },
    });
    await api("/api/admin/settings/billing-est", {
      method: "POST",
      body: { cacheReadTokens: estCacheRead, inputTokens: estInput, outputTokens: estOutput },
    });
    await api("/api/admin/settings/community-link", {
      method: "POST",
      body: { link: communityLink },
    });
    await api("/api/admin/settings/mail", {
      method: "POST",
      body: {
        enabled: $("mail-enabled").checked,
        username: $("mail-username").value.trim(),
        authCode: $("mail-auth-code").value.trim(),
        resendIntervalSeconds: mailResend,
        codeTtlSeconds: mailTtl,
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
