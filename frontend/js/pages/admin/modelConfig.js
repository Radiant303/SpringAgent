/**
 * 模型配置页面。
 *
 * @author hanbing
 * @since 2026-10-13
 */

import { $, h, icon } from "../../utils/dom.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { openModelDialog } from "./modelDialog.js";

let modelConfig = { providers: [], models: [], defaultModel: "", subagentModels: {} };
let currentProvider = null;
const availableCache = new Map();
let availState = "idle";
let availError = "";
let searchKeyword = "";

const CAP_LABELS = {
  thinking: "思考",
  image_in: "图像",
};
const PROVIDER_TYPES = ["openai", "alibaba", "deepseek", "responses"];

/* ---------- 通用小件 ---------- */

function iconBtn(iconName, act, text) {
  const b = h("button", "icon-btn");
  b.type = "button";
  b.dataset.act = act;
  b.title = text;
  b.appendChild(icon(iconName));
  return b;
}

function setBtnLoading(btn, loading) {
  if (!btn) return;
  if (loading) {
    btn.dataset.prevHtml = btn.innerHTML;
    btn.dataset.prevWidth = btn.style.minWidth || "";
    btn.style.minWidth = `${btn.offsetWidth}px`;
    btn.innerHTML = '<span class="spinner"></span>';
    btn.disabled = true;
    btn.classList.add("is-loading");
  } else {
    if (btn.dataset.prevHtml) btn.innerHTML = btn.dataset.prevHtml;
    btn.style.minWidth = btn.dataset.prevWidth || "";
    delete btn.dataset.prevHtml;
    delete btn.dataset.prevWidth;
    btn.disabled = false;
    btn.classList.remove("is-loading");
  }
}

function chip(text) {
  return h("span", "mc-chip", text);
}

function emptyTip(text) {
  return h("div", "mc-empty", text);
}

/**
 * 渲染 Provider 列表骨架屏
 *
 * @param {HTMLElement} list 列表容器
 * @param {number} [n=3] 骨架行数
 */
function providerListSkeleton(list, n = 3) {
  list.textContent = "";
  const wrap = h("div", "sk-fade");
  wrap.setAttribute("aria-hidden", "true");
  for (let i = 0; i < n; i++) {
    const row = h("div", "mc-sk-row");
    const main = h("span", "mc-row-main");
    const titleRow = h("span", "mc-row-title");
    const titleSk = h("span", "sk");
    titleSk.style.setProperty("--w", "100px");
    titleSk.style.setProperty("--h", "14px");
    const badgeSk = h("span", "sk");
    badgeSk.style.setProperty("--w", "48px");
    badgeSk.style.setProperty("--h", "18px");
    badgeSk.style.setProperty("--r", "6px");
    badgeSk.style.marginLeft = "6px";
    titleRow.append(titleSk, badgeSk);

    const subSk = h("span", "sk");
    subSk.style.setProperty("--w", "200px");
    subSk.style.setProperty("--h", "12px");
    main.append(titleRow, subSk);

    const metaSk = h("span", "sk");
    metaSk.style.setProperty("--w", "50px");
    metaSk.style.setProperty("--h", "12px");

    const acts = h("span", "mc-row-acts");
    const btn1 = h("span", "sk");
    btn1.style.setProperty("--w", "35px");
    btn1.style.setProperty("--h", "35px");
    btn1.style.setProperty("--r", "50%");
    const btn2 = h("span", "sk");
    btn2.style.setProperty("--w", "35px");
    btn2.style.setProperty("--h", "35px");
    btn2.style.setProperty("--r", "50%");
    acts.append(btn1, btn2);

    const goSk = h("span", "sk");
    goSk.style.setProperty("--w", "16px");
    goSk.style.setProperty("--h", "16px");

    row.append(main, metaSk, acts, goSk);
    wrap.appendChild(row);
  }
  list.appendChild(wrap);
}

/**
 * 渲染可用模型列表骨架屏
 *
 * @param {HTMLElement} list 列表容器
 * @param {number} [n=5] 骨架行数
 */
function availListSkeleton(list, n = 5) {
  list.textContent = "";
  const wrap = h("div", "sk-fade");
  wrap.setAttribute("aria-hidden", "true");
  const widths = ["190px", "260px", "160px", "230px", "180px"];
  for (let i = 0; i < n; i++) {
    const row = h("div", "mc-sk-row");
    const main = h("span", "mc-row-main");
    const titleSk = h("span", "sk");
    titleSk.style.setProperty("--w", widths[i % widths.length]);
    titleSk.style.setProperty("--h", "14px");
    main.appendChild(titleSk);

    const acts = h("span", "mc-row-acts");
    const btnSk = h("span", "sk");
    btnSk.style.setProperty("--w", "35px");
    btnSk.style.setProperty("--h", "35px");
    btnSk.style.setProperty("--r", "50%");
    acts.appendChild(btnSk);

    row.append(main, acts);
    wrap.appendChild(row);
  }
  list.appendChild(wrap);
}

function formatK(n) {
  return n >= 1024 ? Math.round(n / 1024) + "K" : String(n);
}

function matches(...texts) {
  const k = searchKeyword.trim().toLowerCase();
  if (!k) return true;
  return texts.some((t) => (t || "").toLowerCase().includes(k));
}

/* ---------- 数据 ---------- */

async function refreshConfig() {
  const list = $("mc-provider-list");
  if (list && !list.querySelector(".mc-row")) providerListSkeleton(list);
  else if (list) list.classList.add("fetching");
  try {
    modelConfig = (await api("/api/admin/model-config")) || modelConfig;
  } catch (err) {
    if (list && !list.querySelector(".mc-row")) {
      list.textContent = "";
      list.appendChild(emptyTip("加载失败"));
    }
    throw err;
  } finally {
    if (list) list.classList.remove("fetching");
  }
  renderDefault();
  renderSubagents();
  renderProviders();
  if (currentProvider) renderDetail();
}

export async function loadModelConfig() {
  showHome();
  await refreshConfig();
}

/* ---------- 一级页：默认模型 / 子代理模型 ---------- */

function renderDefault() {
  const sel = $("mc-default");
  if (!sel) return;
  sel.textContent = "";
  if (!modelConfig.defaultModel) {
    const opt = document.createElement("option");
    opt.value = "";
    opt.textContent = "未设置";
    sel.appendChild(opt);
  }
  for (const m of modelConfig.models.filter((m) => m.enabled)) {
    const opt = document.createElement("option");
    opt.value = m.id;
    opt.textContent = `${m.display_name}（${m.id}）`;
    sel.appendChild(opt);
  }
  sel.value = modelConfig.defaultModel;
  UI.enhance();
}

async function onMcDefaultChange() {
  const modelId = $("mc-default").value;
  if (!modelId || modelId === modelConfig.defaultModel) return;
  try {
    await api("/api/admin/model-config/default", { method: "POST", body: { modelId } });
    modelConfig.defaultModel = modelId;
    // 本地同步默认标记，避免二级页徽章与开关状态滞后
    for (const m of modelConfig.models) m.is_default = m.id === modelId;
    if (currentProvider) renderAddedList();
    UI.toast("默认模型已切换");
  } catch (err) {
    UI.toast(err.message, { type: "error" });
    await refreshConfig();
  }
}

function fillSubagentSelect(sel, value) {
  sel.textContent = "";
  const inherit = document.createElement("option");
  inherit.value = "";
  inherit.textContent = "继承主模型";
  sel.appendChild(inherit);
  for (const m of modelConfig.models.filter((m) => m.enabled)) {
    const opt = document.createElement("option");
    opt.value = m.id;
    opt.textContent = `${m.display_name}（${m.id}）`;
    sel.appendChild(opt);
  }
  sel.value = value || "";
  UI.enhance();
}

function renderSubagents() {
  const sub = modelConfig.subagentModels || {};
  for (const [elId, name] of [["mc-sub-searcher", "searcher"], ["mc-sub-reviewer", "reviewer"]]) {
    const sel = $(elId);
    if (sel) fillSubagentSelect(sel, sub[name]);
  }
}

async function onMcSubagentChange(name) {
  const sel = name === "searcher" ? $("mc-sub-searcher") : $("mc-sub-reviewer");
  if (!sel) return;
  try {
    await api("/api/admin/model-config/subagent-models", {
      method: "POST",
      body: { models: { [name]: sel.value } },
    });
    if (!modelConfig.subagentModels) modelConfig.subagentModels = {};
    modelConfig.subagentModels[name] = sel.value;
    UI.toast("已保存");
  } catch (err) {
    UI.toast(err.message, { type: "error" });
    await refreshConfig();
  }
}

/* ---------- 一级页：Provider 列表 ---------- */

function renderProviders() {
  const list = $("mc-provider-list");
  if (!list) return;
  list.textContent = "";
  if (!modelConfig.providers.length) {
    list.appendChild(emptyTip("暂无 Provider，点击上方按钮添加"));
    return;
  }
  for (const p of modelConfig.providers) {
    const count = modelConfig.models.filter((m) => m.provider === p.name).length;
    const row = h("div", "mc-row mc-provider");
    row.dataset.name = p.name;

    const main = h("span", "mc-row-main");
    const title = h("span", "mc-row-title");
    title.append(p.name, h("span", "badge", p.type));
    const sub = h("span", "mc-row-sub", p.effective_base_url);
    sub.title = p.effective_base_url;
    main.append(title, sub);

    const meta = h("span", "mc-row-meta", `${count} 个模型`);
    const acts = h("span", "mc-row-acts");
    acts.append(iconBtn("gear", "edit", "编辑"), iconBtn("trash", "delete", "删除"));
    row.append(main, meta, acts, icon("chevron", "mc-go"));
    list.appendChild(row);
  }
}

async function openProviderDialog(p) {
  const r = await UI.dialog({
    title: p ? "编辑 Provider" : "添加 Provider",
    fields: [
      { name: "name", label: "名称", value: p ? p.name : "", disabled: !!p, placeholder: "如 deepseek" },
      { name: "type", label: "类型", type: "select", options: PROVIDER_TYPES.map((t) => ({ value: t, label: t })), value: p ? p.type : "openai" },
      { name: "apiKey", label: "API Key", type: "password", autocomplete: "new-password", placeholder: p ? (p.api_key_configured ? "已配置，留空保持不变" : "未配置，请填写") : "API Key（必填）" },
      { name: "baseUrl", label: "base_url", value: p ? p.base_url || "" : "", hint: p?.base_url ? "留空保持当前地址" : "留空使用输入框提示的默认地址" },
    ],
    onChange: (v, inputs) => {
      inputs.baseUrl.placeholder = p?.base_url || modelConfig.providerDefaults[v.type];
    },
    okText: p ? "保存" : "添加",
    validate: (v) => {
      if (!/^[a-z0-9][a-z0-9-]*$/.test(v.name.trim())) {
        return { name: "name", message: "名称只能含小写字母、数字与连字符，且以字母或数字开头" };
      }
      if (!p && !v.apiKey.trim()) {
        return { name: "apiKey", message: "新建 Provider 必须填写 API Key" };
      }
      return null;
    },
    submit: (v) => api("/api/admin/model-config/providers", {
      method: "POST",
      body: { name: v.name.trim(), type: v.type, apiKey: v.apiKey.trim(), baseUrl: v.baseUrl.trim() },
    }),
  });
  if (!r) return;
  UI.toast("Provider 已保存");
  availableCache.delete(p ? p.name : r.name.trim());
  await refreshConfig();
}

async function deleteProvider(name) {
  const ok = await UI.confirm({
    title: "删除 Provider",
    message: `确定删除 Provider「${name}」吗？`,
    okText: "删除",
    danger: true,
    submit: () => api(`/api/admin/model-config/providers/${encodeURIComponent(name)}/delete`, { method: "POST" }),
  });
  if (!ok) return;
  UI.toast("Provider 已删除");
  availableCache.delete(name);
  await refreshConfig();
}

/* ---------- 二级页：进入 / 返回 ---------- */

function enterProvider(name) {
  if (!modelConfig.providers.some((p) => p.name === name)) return;
  currentProvider = name;
  searchKeyword = "";
  availState = availableCache.has(name) ? "done" : "idle";
  availError = "";
  const search = $("mc-search");
  if (search) search.value = "";
  document.querySelector('.admin-page[data-page="model-config"]')?.classList.remove("active");
  document.querySelector('.admin-page[data-page="mc-provider"]')?.classList.add("active");
  document.title = `${name} - Spring Harness`;
  const main = $("admin-main");
  if (main) main.scrollTop = 0;
  renderDetail();
  fetchAvailable(false);
}

function showHome() {
  currentProvider = null;
  document.querySelector('.admin-page[data-page="mc-provider"]')?.classList.remove("active");
  document.querySelector('.admin-page[data-page="model-config"]')?.classList.add("active");
  document.title = "模型配置 - Spring Harness";
}

/* ---------- 二级页：渲染 ---------- */

function providerModels() {
  return modelConfig.models.filter((m) => m.provider === currentProvider);
}

function renderDetail() {
  const p = modelConfig.providers.find((x) => x.name === currentProvider);
  if (!p) {
    // Provider 刚被删除，二级页失去上下文，回一级
    showHome();
    return;
  }
  $("mc-detail-name").textContent = p.name;
  $("mc-detail-type").textContent = p.type;
  const url = $("mc-detail-url");
  url.textContent = p.effective_base_url;
  url.title = p.effective_base_url;
  renderAddedList();
  renderAvailList();
}

function renderAddedList() {
  const all = providerModels();
  $("mc-added-count").textContent = all.length;
  const list = $("mc-added-list");
  list.textContent = "";
  const rows = all.filter((m) => matches(m.id, m.display_name, m.model));
  if (!rows.length) {
    list.appendChild(emptyTip(all.length ? "没有匹配的模型" : "暂无已配置的模型"));
    return;
  }
  for (const m of rows) {
    const row = h("div", "mc-row");
    row.dataset.id = m.id;

    const main = h("span", "mc-row-main");
    const title = h("span", "mc-row-title");
    title.append(m.display_name);
    if (m.is_default) title.append(h("span", "badge", "默认"));
    const sub = h("span", "mc-row-sub", m.model);
    sub.title = m.model;
    const chips = h("span", "mc-row-chips");
    chips.appendChild(chip(formatK(m.max_context_size)));
    if (m.max_output_size > 0) chips.appendChild(chip(`输出 ${formatK(m.max_output_size)}`));
    for (const c of m.capabilities) chips.appendChild(chip(CAP_LABELS[c] || c));
    main.append(title, sub, chips);

    const acts = h("span", "mc-row-acts");
    const swLabel = h("label", "mc-sw");
    const sw = h("input");
    sw.type = "checkbox";
    sw.className = "switch";
    sw.checked = !!m.enabled;
    sw.disabled = m.is_default;
    sw.dataset.act = "toggle";
    swLabel.appendChild(sw);
    if (m.is_default) swLabel.title = "默认模型不可停用";
    acts.append(swLabel, iconBtn("plug", "test", "测试连接"), iconBtn("gear", "edit", "编辑"));
    if (!m.is_default) acts.appendChild(iconBtn("trash", "delete", "删除"));
    row.append(main, acts);
    list.appendChild(row);
  }
}

function renderAvailList() {
  const list = $("mc-avail-list");
  if (!list) return;
  const count = $("mc-avail-count");
  list.textContent = "";
  if (availState === "idle") {
    count.textContent = "";
    list.appendChild(emptyTip("点击「获取模型列表」拉取该 Provider 支持的模型"));
    return;
  }
  if (availState === "loading") {
    count.textContent = "";
    availListSkeleton(list, 5);
    return;
  }
  if (availState === "error") {
    count.textContent = "";
    list.appendChild(emptyTip(`拉取失败：${availError}`));
    return;
  }
  const all = availableCache.get(currentProvider) || [];
  const configured = new Set(providerModels().map((m) => m.model));
  const fresh = all.filter((id) => !configured.has(id));
  count.textContent = fresh.length;
  const rows = fresh.filter((id) => matches(id));
  if (!rows.length) {
    list.appendChild(emptyTip(fresh.length ? "没有匹配的模型" : "该 Provider 暂无可添加的模型"));
    return;
  }
  for (const id of rows) {
    const row = h("div", "mc-row");
    row.dataset.model = id;
    const main = h("span", "mc-row-main");
    const title = h("span", "mc-row-title", id);
    main.appendChild(title);
    row.append(main, h("span", "mc-row-acts"));
    row.lastChild.appendChild(iconBtn("plus", "add", "添加"));
    list.appendChild(row);
  }
}

/* ---------- 二级页：拉取可用模型 ---------- */

async function fetchAvailable(force) {
  const name = currentProvider;
  if (!name) return;
  if (!force && availableCache.has(name)) {
    availState = "done";
    availError = "";
    renderAvailList();
    return;
  }
  availState = "loading";
  availError = "";
  renderAvailList();
  const btn = $("mc-fetch");
  setBtnLoading(btn, true);
  try {
    const data = await api(`/api/admin/model-config/providers/${encodeURIComponent(name)}/available-models`);
    availableCache.set(name, (data && data.models) || []);
    availState = "done";
  } catch (err) {
    availState = "error";
    availError = err.message;
  } finally {
    setBtnLoading(btn, false);
    // 拉取期间用户可能已切到别的 Provider，只对停留的页面重渲染
    if (currentProvider === name) renderAvailList();
  }
}

/* ---------- 二级页：模型操作 ---------- */

function modelBody(m, overrides = {}) {
  return {
    originalId: m.id,
    provider: m.provider,
    model: m.model,
    displayName: m.display_name,
    maxContextSize: m.max_context_size,
    maxOutputSize: m.max_output_size,
    capabilities: m.capabilities,
    supportEfforts: m.support_efforts,
    defaultEffort: m.default_effort,
    enabled: m.enabled,
    ...overrides,
  };
}

async function toggleEnabled(m, sw) {
  try {
    await api("/api/admin/model-config/models", { method: "POST", body: modelBody(m, { enabled: sw.checked }) });
    m.enabled = sw.checked;
    UI.toast(sw.checked ? "已启用" : "已停用");
  } catch (err) {
    sw.checked = !sw.checked;
    UI.toast(err.message, { type: "error" });
  }
}

async function testModel(m, btn) {
  setBtnLoading(btn, true);
  try {
    const r = await api("/api/admin/model-config/models/test", { method: "POST", body: { modelId: m.id } });
    if (r && r.ok) UI.toast(`模型 ${m.model} 测试通过，延迟 ${r.latency_ms} ms`);
    else UI.toast(`测试失败：${(r && r.error) || "未知错误"}`, { type: "error", duration: 6000 });
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  } finally {
    setBtnLoading(btn, false);
  }
}

async function deleteModel(m) {
  const ok = await UI.confirm({
    title: "删除模型",
    message: `确定删除模型「${m.model}」吗？\n存量会话若仍引用它将无法继续对话。`,
    okText: "删除",
    danger: true,
    submit: () => api("/api/admin/model-config/models/delete", { method: "POST", body: { modelId: m.id } }),
  });
  if (!ok) return;
  UI.toast("模型已删除");
  await refreshConfig();
}

function openAddModel({ model, displayName = "" }) {
  if (providerModels().some((m) => m.model === model)) {
    UI.toast(`模型 ${model} 已配置`, { type: "error" });
    return;
  }
  openModelDialog({
    thinkingEfforts: modelConfig.thinkingEfforts,
    mode: "add",
    provider: currentProvider,
    model,
    displayName,
    onSaved: async () => {
      UI.toast("模型已添加");
      await refreshConfig();
    },
  });
}

function openEditModel(m) {
  openModelDialog({
    thinkingEfforts: modelConfig.thinkingEfforts,
    mode: "edit",
    provider: m.provider,
    model: m.model,
    existing: m,
    onSaved: async () => {
      UI.toast("模型已保存");
      await refreshConfig();
    },
  });
}

async function openCustomModel() {
  const r = await UI.dialog({
    title: "自定义模型",
    fields: [
      { name: "model", label: "模型 ID", placeholder: "API 请求用的模型名，如 Qwen/Qwen3-8B" },
      { name: "displayName", label: "展示名称", placeholder: "选填，留空用模型 ID" },
    ],
    okText: "下一步",
    validate: (v) => (!v.model.trim() ? { name: "model", message: "模型 ID 不能为空" } : null),
  });
  if (!r) return;
  openAddModel({ model: r.model.trim(), displayName: r.displayName.trim() });
}

/* ---------- 事件绑定 ---------- */

export function bindModelConfig() {
  const back = $("mc-back");
  if (back) back.appendChild(icon("back"));
  const fetchBtn = $("mc-fetch");
  if (fetchBtn) fetchBtn.append(icon("download"), "获取模型列表");

  $("mc-provider-add")?.addEventListener("click", () => openProviderDialog(null));
  $("mc-provider-list")?.addEventListener("click", (e) => {
    const row = e.target.closest(".mc-row");
    if (!row) return;
    const name = row.dataset.name;
    const btn = e.target.closest("button[data-act]");
    if (!btn) {
      enterProvider(name);
      return;
    }
    if (btn.dataset.act === "edit") {
      openProviderDialog(modelConfig.providers.find((p) => p.name === name) || null);
    } else if (btn.dataset.act === "delete") {
      deleteProvider(name);
    }
  });

  $("mc-back")?.addEventListener("click", showHome);
  $("mc-fetch")?.addEventListener("click", () => fetchAvailable(true));
  $("mc-custom")?.addEventListener("click", openCustomModel);
  $("mc-search")?.addEventListener("input", (e) => {
    searchKeyword = e.target.value;
    renderAddedList();
    renderAvailList();
  });

  $("mc-added-list")?.addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-act]");
    if (!btn) return;
    const id = btn.closest(".mc-row")?.dataset.id;
    const m = modelConfig.models.find((x) => x.id === id);
    if (!m) return;
    if (btn.dataset.act === "edit") openEditModel(m);
    else if (btn.dataset.act === "delete") deleteModel(m);
    else if (btn.dataset.act === "test") testModel(m, btn);
  });
  $("mc-added-list")?.addEventListener("change", (e) => {
    const sw = e.target.closest('input.switch[data-act="toggle"]');
    if (!sw) return;
    const id = sw.closest(".mc-row")?.dataset.id;
    const m = modelConfig.models.find((x) => x.id === id);
    if (m) toggleEnabled(m, sw);
  });
  $("mc-avail-list")?.addEventListener("click", (e) => {
    const btn = e.target.closest('button[data-act="add"]');
    if (!btn) return;
    const model = btn.closest(".mc-row")?.dataset.model;
    if (model) openAddModel({ model });
  });

  $("mc-default")?.addEventListener("change", onMcDefaultChange);
  $("mc-sub-searcher")?.addEventListener("change", () => onMcSubagentChange("searcher"));
  $("mc-sub-reviewer")?.addEventListener("change", () => onMcSubagentChange("reviewer"));
}
