/**
 * 站长设置各页业务逻辑。
 */

import { $ } from "../../utils/dom.js";
import { parseInteger } from "../../utils/numeric.js";
import { loadResourceLimits, quotaValue, pointsValue, pointsHint } from "../../utils/resourceLimits.js";
import { getRole } from "../../api/auth.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";

/** 页面骨架：加载期间禁用保存按钮，避免先看到空值 */
async function withSkeleton(page, saveId, fn) {
  if (getRole() !== "owner") return null;
  const section = document.querySelector(`.admin-page[data-page="${page}"]`);
  section?.classList.add("is-loading");
  if ($(saveId)) $(saveId).disabled = true;
  try {
    return await fn();
  } finally {
    section?.classList.remove("is-loading");
    if ($(saveId)) $(saveId).disabled = false;
  }
}

/** 保存设置 */
async function saveWith(saveId, fn) {
  if ($(saveId)?.disabled) return;
  if ($(saveId)) $(saveId).disabled = true;
  try {
    const saved = await fn();
    if (saved !== false) UI.toast("设置已保存");
  } catch (e) {
    UI.toast(e.message, { type: "error" });
  } finally {
    if ($(saveId)) $(saveId).disabled = false;
  }
}

/* ================= 注册设置 ================= */

let registrationReady = false;
let savedMailEnabled = false;
let ownerEmail = "";

function mailDisableCountdown(btn, seconds) {
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

async function sendMailDisableCode(btn) {
  if (btn.disabled) return;
  btn.disabled = true;
  btn.textContent = "发送中…";
  try {
    const data = await api("/api/admin/settings/mail/send-disable-code", { method: "POST" });
    mailDisableCountdown(btn, (data && data.resendAfterSeconds) || 60);
    UI.toast("验证码已发送，请查收邮件");
  } catch (e) {
    btn.disabled = false;
    btn.textContent = "获取验证码";
    UI.toast(e.message, { type: "error" });
  }
}

async function saveRegistrationSetting(open) {
  await api("/api/admin/settings/registration", { method: "POST", body: { open } });
}

async function saveMailSettings(body, code) {
  await api("/api/admin/settings/mail", {
    method: "POST",
    body: code === undefined ? body : { ...body, code },
  });
  savedMailEnabled = body.enabled;
  if ($("mail-auth-code")) $("mail-auth-code").value = "";
}

export async function loadRegistration() {
  registrationReady = false;
  const result = await withSkeleton("registration", "registration-save", () =>
    Promise.all([api("/api/admin/settings"), api("/api/account/me")]));
  if (!result) return;
  const [data, account] = result;
  if (!data || !account) throw new Error("注册设置加载失败，请重新进入页面");
  if ($("registration-open")) $("registration-open").checked = data.registrationOpen !== false;
  if ($("mail-enabled")) $("mail-enabled").checked = data.mailRegisterEnabled === true;
  if ($("mail-username")) $("mail-username").value = data.mailUsername ?? "";
  // 授权码只写不读：不回显原值，留空表示保持不变
  if ($("mail-auth-code")) {
    $("mail-auth-code").value = "";
    $("mail-auth-code").placeholder = data.mailAuthCodeConfigured ? "已配置，留空保持不变" : "未配置";
  }
  if ($("mail-resend-interval")) $("mail-resend-interval").value = data.mailResendIntervalSeconds ?? 60;
  if ($("mail-code-ttl")) $("mail-code-ttl").value = data.mailCodeTtlSeconds ?? 300;
  savedMailEnabled = data.mailRegisterEnabled === true;
  ownerEmail = typeof account.email === "string" ? account.email : "";
  registrationReady = true;
}

export async function saveRegistration() {
  if (!registrationReady) {
    UI.toast("设置尚未加载完成，请重新进入页面", { type: "error" });
    return;
  }
  await saveWith("registration-save", async () => {
    const mailResend = parseInteger($("mail-resend-interval").value, { min: 10, max: 3600 });
    const mailTtl = parseInteger($("mail-code-ttl").value, { min: 60, max: 3600 });
    if (mailResend === null || mailTtl === null) {
      throw new Error("重发间隔须为 10～3600 秒、验证码有效期须为 60～3600 秒的整数");
    }
    const registrationOpen = $("registration-open").checked;
    const mail = {
      enabled: $("mail-enabled").checked,
      username: $("mail-username").value.trim(),
      authCode: $("mail-auth-code").value.trim(),
      resendIntervalSeconds: mailResend,
      codeTtlSeconds: mailTtl,
    };
    if (!(savedMailEnabled && !mail.enabled && ownerEmail)) {
      await saveRegistrationSetting(registrationOpen);
      await saveMailSettings(mail);
      return;
    }
    const values = await UI.dialog({
      title: "关闭邮箱功能",
      message: `验证码将发送至绑定邮箱 ${ownerEmail}`,
      fields: [
        {
          name: "code", label: "邮箱验证码", placeholder: "6 位数字", inputMode: "numeric", maxLength: 6,
          suffixBtn: { text: "获取验证码", onClick: (btn) => sendMailDisableCode(btn) },
        },
      ],
      okText: "关闭并保存",
      validate: (v) => /^\d{6}$/.test(v.code.trim()) ? null : { message: "请输入 6 位数字验证码", name: "code" },
      submit: async (v) => {
        await saveMailSettings(mail, v.code.trim());
        try {
          await saveRegistrationSetting(registrationOpen);
        } catch {
          throw new Error("邮箱设置已保存，但开放注册设置保存失败，请重试");
        }
      },
    });
    return values === null ? false : undefined;
  });
}

/* ================= 存储设置 ================= */

/** 存储类型为本地时隐藏 OSS 配置行 */
function syncOssRows() {
  const section = document.querySelector('.admin-page[data-page="storage"]');
  section?.classList.toggle("oss-hidden", $("storage-type")?.value !== "aliyun_oss");
}

/** 一次性绑定 */
export function bindStorageSettings() {
  $("storage-type")?.addEventListener("change", syncOssRows);
}

export async function loadStorage() {
  const [data, limits] = await withSkeleton("storage", "storage-save", () =>
    Promise.all([api("/api/admin/settings"), loadResourceLimits()]));
  if ($("work-max-mb")) {
    $("work-max-mb").max = limits.quotaMbMax;
    $("work-max-mb").step = "1";
    $("work-max-mb").title = `1～${limits.quotaMbMax} MB，整数`;
    $("work-max-mb").value = data && data.workMaxBytes != null
      ? Number(data.workMaxBytes) / limits.mbBytes
      : 20;
  }
  if ($("storage-type")) {
    $("storage-type").value = data?.storageType ?? "local";
    // 自绘下拉与原生值同步，再联动 OSS 行显隐
    $("storage-type").dispatchEvent(new Event("change", { bubbles: true }));
  }
  if ($("oss-endpoint")) $("oss-endpoint").value = data?.ossEndpoint ?? "";
  if ($("oss-bucket")) $("oss-bucket").value = data?.ossBucket ?? "";
  if ($("oss-access-key-id")) $("oss-access-key-id").value = data?.ossAccessKeyId ?? "";
  // AccessKey Secret 只写不读：不回显原值，留空表示保持不变
  if ($("oss-access-key-secret")) {
    $("oss-access-key-secret").value = "";
    $("oss-access-key-secret").placeholder = data && data.ossAccessKeySecretConfigured ? "已配置，留空保持不变" : "未配置";
  }
  if ($("storage-reconcile-cron")) {
    $("storage-reconcile-cron").value = data?.storageReconcileCron ?? "";
  }
  syncOssRows();
}

export async function saveStorage() {
  await saveWith("storage-save", async () => {
    const limits = await loadResourceLimits();
    const mb = quotaValue($("work-max-mb")?.value, limits, { min: 1 });
    if (mb === null) throw new Error(`单工作区配额须为 1～${limits.quotaMbMax} MB 的整数`);
    const ossEndpoint = $("oss-endpoint").value.trim();
    if (ossEndpoint && !/^https?:\/\//.test(ossEndpoint)) {
      throw new Error("OSS endpoint 必须以 http:// 或 https:// 开头");
    }
    const reconcileCron = $("storage-reconcile-cron").value.trim();
    if (reconcileCron && reconcileCron.split(/\s+/).length !== 6) {
      throw new Error("对账 cron 须为六段式：秒 分 时 日 月 周");
    }
    await api("/api/admin/settings/work-max-bytes", {
      method: "POST",
      body: { bytes: mb * limits.mbBytes },
    });
    await api("/api/admin/settings/storage", {
      method: "POST",
      body: {
        type: $("storage-type").value,
        endpoint: ossEndpoint,
        bucket: $("oss-bucket").value.trim(),
        accessKeyId: $("oss-access-key-id").value.trim(),
        accessKeySecret: $("oss-access-key-secret").value.trim(),
        reconcileCron,
      },
    });
  });
}

/* ================= 计费设置 ================= */

export async function loadBilling() {
  const [data, limits] = await withSkeleton("billing", "billing-save", () =>
    Promise.all([api("/api/admin/settings"), loadResourceLimits()]));
  for (const id of ["est-cache-read", "est-input", "est-output"]) {
    if ($(id)) {
      $(id).max = limits.billingTokensMax;
      $(id).step = "1";
      $(id).title = `0～${limits.billingTokensMax} tokens，整数`;
    }
  }
  if ($("est-cache-read")) $("est-cache-read").value = data?.billingEstCacheReadTokens ?? 90000;
  if ($("est-input")) $("est-input").value = data?.billingEstInputTokens ?? 10000;
  if ($("est-output")) $("est-output").value = data?.billingEstOutputTokens ?? 20000;
}

export async function saveBilling() {
  await saveWith("billing-save", async () => {
    const limits = await loadResourceLimits();
    const token = (id) => parseInteger($(id)?.value, { max: limits.billingTokensMax });
    const estCacheRead = token("est-cache-read");
    const estInput = token("est-input");
    const estOutput = token("est-output");
    if ([estCacheRead, estInput, estOutput].includes(null)) {
      throw new Error(`冻结预估须为 0～${limits.billingTokensMax} tokens 的整数`);
    }
    await api("/api/admin/settings/billing-est", {
      method: "POST",
      body: { cacheReadTokens: estCacheRead, inputTokens: estInput, outputTokens: estOutput },
    });
  });
}

/* ================= 平台设置 ================= */

export async function loadPlatform() {
  const data = await withSkeleton("platform", "platform-save", () => api("/api/admin/settings"));
  if ($("platform-domain")) $("platform-domain").value = data?.platformDomain ?? "";
  if ($("platform-https")) $("platform-https").checked = !!data && data.platformHttps === true;
}

export async function savePlatform() {
  await saveWith("platform-save", async () => {
    const domain = $("platform-domain").value.trim();
    if (domain && !/^[A-Za-z0-9.-]+(:[0-9]{1,5})?$/.test(domain)) {
      throw new Error("域名格式不正确：只填主机名（可带端口），不要带 http:// 或路径");
    }
    await api("/api/admin/settings/platform", {
      method: "POST",
      body: { domain, https: $("platform-https").checked },
    });
  });
}

/* ================= 社群设置 ================= */

export async function loadCommunity() {
  const data = await withSkeleton("community", "community-save", () => api("/api/admin/settings"));
  if ($("community-link")) $("community-link").value = data?.communityLink ?? "";
}

export async function saveCommunity() {
  await saveWith("community-save", async () => {
    const communityLink = $("community-link").value.trim();
    if (communityLink && !/^https?:\/\//.test(communityLink)) {
      throw new Error("社群链接必须以 http:// 或 https:// 开头");
    }
    await api("/api/admin/settings/community-link", {
      method: "POST",
      body: { link: communityLink },
    });
  });
}

let searchDraft = null;
let searchProvider = "";
let searchReady = false;
let searchBusy = false;
let searchLimits;

function stashSearchProvider() {
  const draft = searchDraft?.[searchProvider];
  if (!draft) return;
  draft.apiKey = $("search-api-key").value;
  draft.points = $("search-provider-points").value;
}

function renderSearchProvider() {
  const draft = searchDraft?.[searchProvider];
  if (!draft) return;
  $("search-api-key").value = draft.apiKey;
  $("search-api-key").placeholder = draft.apiKeyConfigured ? "已配置，留空保持不变" : "未配置";
  $("search-provider-points").value = draft.points;
}

export function bindSearchSettings() {
  $("search-local-provider")?.addEventListener("change", () => {
    if (!searchReady || searchBusy) return;
    stashSearchProvider();
    searchProvider = $("search-local-provider").value;
    renderSearchProvider();
  });
}

export async function loadSearch() {
  if (getRole() !== "owner" || searchBusy) return;
  searchBusy = true;
  searchReady = false;
  const section = document.querySelector('.admin-page[data-page="search"]');
  section?.classList.add("is-loading");
  $("search-fields").disabled = true;
  $("search-local-provider").disabled = true;
  $("search-save").disabled = true;
  try {
    const [data, limits] = await Promise.all([api("/api/admin/settings"), loadResourceLimits()]);
    const search = data?.search;
    const providers = Object.entries(search?.providers ?? {});
    if (!providers.length || !Object.hasOwn(search.providers, search.localProvider)) {
      throw new Error("搜索设置加载失败，请重新进入页面");
    }
    const draft = Object.fromEntries(providers.map(([id, provider]) => {
      if (typeof provider.label !== "string" || typeof provider.points !== "string") {
        throw new Error("搜索设置加载失败，请重新进入页面");
      }
      return [id, { ...provider, apiKey: "" }];
    }));
    if (typeof search.nativePoints !== "string" || typeof search.localEnabled !== "boolean") {
      throw new Error("搜索设置加载失败，请重新进入页面");
    }
    searchLimits = limits;
    searchDraft = draft;
    searchProvider = search.localProvider;
    $("search-local-provider").replaceChildren(...providers.map(([id, provider]) => new Option(provider.label, id)));
    $("search-local-provider").value = searchProvider;
    $("search-local-enabled").checked = search.localEnabled;
    $("search-native-points").value = search.nativePoints;
    for (const id of ["search-provider-points", "search-native-points"]) {
      $(id).title = pointsHint(limits);
    }
    renderSearchProvider();
    searchReady = true;
  } finally {
    searchBusy = false;
    section?.classList.remove("is-loading");
    $("search-fields").disabled = !searchReady;
    $("search-local-provider").disabled = !searchReady;
    $("search-save").disabled = !searchReady;
  }
}

export async function saveSearch() {
  if (getRole() !== "owner" || !searchReady || searchBusy) return;
  await saveWith("search-save", async () => {
    searchBusy = true;
    $("search-fields").disabled = true;
    $("search-local-provider").disabled = true;
    try {
      stashSearchProvider();
      const nativePoints = pointsValue($("search-native-points").value, searchLimits);
      if (nativePoints === null) throw new Error("原生搜索积分须为 " + pointsHint(searchLimits));
      const providers = Object.fromEntries(Object.entries(searchDraft).map(([id, draft]) => {
        const points = pointsValue(draft.points, searchLimits);
        if (points === null) throw new Error(draft.label + " 搜索积分须为 " + pointsHint(searchLimits));
        if (draft.apiKey.length > 1024) throw new Error("API key 最多 1024 个字符");
        return [id, { apiKey: draft.apiKey, points }];
      }));
      await api("/api/admin/settings/search", {
        method: "POST",
        body: { localEnabled: $("search-local-enabled").checked, localProvider: searchProvider, nativePoints, providers },
      });
      for (const draft of Object.values(searchDraft)) {
        draft.apiKeyConfigured = !!draft.apiKey.trim() || draft.apiKeyConfigured;
        draft.apiKey = "";
      }
      renderSearchProvider();
    } finally {
      searchBusy = false;
      $("search-fields").disabled = false;
      $("search-local-provider").disabled = false;
    }
  });
}
