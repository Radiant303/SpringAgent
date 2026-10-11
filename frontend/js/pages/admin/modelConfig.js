/**
 * 模型配置（Upstream Providers & Model Definitions）
 */

import { $ } from "../../utils/dom.js";
import { fmtTime } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";

export let modelConfig = { providers: [], models: [], defaultModel: "" };
let editingProviderName = null;
let editingModelDefId = null;

function csvToList(raw) {
  return (raw || "").split(",").map((s) => s.trim()).filter(Boolean);
}

export async function loadModelConfig() {
  const tb1 = $("mc-provider-tbody");
  const tb2 = $("mc-model-tbody");
  const card1 = tb1?.closest(".table-wrap") || tb1?.closest(".table-card");
  const card2 = tb2?.closest(".table-wrap") || tb2?.closest(".table-card");
  const hasRows1 = !!tb1?.querySelector("tr:not(.sk-tr):not(.empty-row)");
  const hasRows2 = !!tb2?.querySelector("tr:not(.sk-tr):not(.empty-row)");
  if (hasRows1 && card1) card1.classList.add("fetching", "is-loading");
  else if (tb1) UI.tableSkeleton(tb1);
  if (hasRows2 && card2) card2.classList.add("fetching", "is-loading");
  else if (tb2) UI.tableSkeleton(tb2);

  try {
    const data = await api("/api/admin/model-config");
    modelConfig = data || { providers: [], models: [], defaultModel: "" };
    renderMcDefault();
    renderMcProviders();
    renderMcModels();
  } catch (e) {
    if (tb1?.querySelector(".sk-tr")) UI.emptyRow(tb1, "加载失败");
    if (tb2?.querySelector(".sk-tr")) UI.emptyRow(tb2, "加载失败");
    throw e;
  } finally {
    if (card1) card1.classList.remove("fetching", "is-loading");
    if (card2) card2.classList.remove("fetching", "is-loading");
  }
}

export function renderMcDefault() {
  const sel = $("mc-default");
  if (!sel) return;
  sel.innerHTML = "";
  const enabled = modelConfig.models.filter((m) => m.enabled);
  if (!modelConfig.defaultModel) {
    const opt = document.createElement("option");
    opt.value = "";
    opt.textContent = "未设置";
    sel.appendChild(opt);
  }
  for (const m of enabled) {
    const opt = document.createElement("option");
    opt.value = m.id;
    opt.textContent = `${m.display_name}（${m.id}）`;
    sel.appendChild(opt);
  }
  sel.value = modelConfig.defaultModel;
  UI.enhance();
}

export function renderMcProviders() {
  UI.renderRows("mc-provider-tbody", modelConfig.providers, (tr, p) => {
    tr.className = "";
    tr.innerHTML = `
      <td></td>
      <td>${p.type}</td>
      <td class="mc-ellipsis"></td>
      <td>${p.api_key_configured ? "已配置" : '<span class="status-disabled">未配置</span>'}</td>
      <td>${fmtTime(p.updated_at)}</td>
      <td><div class="row-actions">
        <button class="admin-btn" data-act="edit" data-name="${p.name}">编辑</button>
        <button class="admin-btn danger" data-act="delete" data-name="${p.name}">删除</button>
      </div></td>`;
    tr.cells[0].textContent = p.name;
    tr.cells[2].textContent = p.base_url || "-";
    if (p.base_url) tr.cells[2].title = p.base_url;
  }, "暂无 Provider");
}

export function fillMcProviderForm(p) {
  if (!$("mc-p-name")) return;
  $("mc-p-name").value = p ? p.name : "";
  $("mc-p-name").disabled = !!p;
  $("mc-p-type").value = p ? p.type : "openai";
  $("mc-p-key").value = "";
  $("mc-p-key").placeholder = p
    ? (p.api_key_configured ? "已配置，留空保持不变" : "未配置，请填写")
    : "API Key（必填）";
  $("mc-p-url").value = p ? (p.base_url || "") : "";
  $("mc-p-submit").textContent = p ? "保存修改" : "新增 Provider";
  $("mc-p-cancel").classList.toggle("hidden", !p);
  editingProviderName = p ? p.name : null;
  UI.enhance();
}

export async function onMcProviderSubmit(e) {
  e.preventDefault();
  const body = {
    name: $("mc-p-name").value.trim(),
    type: $("mc-p-type").value,
    apiKey: $("mc-p-key").value.trim(),
    baseUrl: $("mc-p-url").value.trim(),
  };
  if (!body.name) { UI.toast("Provider 名不能为空", { type: "error" }); return; }
  if (!editingProviderName && !body.apiKey) { UI.toast("新建 Provider 必须填写 API Key", { type: "error" }); return; }
  try {
    await api("/api/admin/model-config/providers", { method: "POST", body });
    fillMcProviderForm(null);
    UI.toast("Provider 已保存");
    await loadModelConfig();
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}

export async function onMcProviderAction(btn) {
  const name = btn.dataset.name;
  try {
    if (btn.dataset.act === "edit") {
      fillMcProviderForm(modelConfig.providers.find((p) => p.name === name) || null);
      $("mc-p-key").focus();
    } else if (btn.dataset.act === "delete") {
      const ok = await UI.confirm({
        title: "删除 Provider",
        message: `确定删除 Provider「${name}」吗？`,
        okText: "删除",
        danger: true,
        submit: () => api(`/api/admin/model-config/providers/${encodeURIComponent(name)}/delete`, { method: "POST" }),
      });
      if (!ok) return;
      if (editingProviderName === name) fillMcProviderForm(null);
      UI.toast("Provider 已删除");
      await loadModelConfig();
    }
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}

export function renderMcModels() {
  const sel = $("mc-m-provider");
  if (sel) {
    const prev = sel.value;
    sel.innerHTML = "";
    for (const p of modelConfig.providers) {
      const opt = document.createElement("option");
      opt.value = p.name;
      opt.textContent = p.name;
      sel.appendChild(opt);
    }
    if (modelConfig.providers.some((p) => p.name === prev)) sel.value = prev;
    UI.enhance();
  }

  UI.renderRows("mc-model-tbody", modelConfig.models, (tr, m) => {
    const actions = [
      `<button class="admin-btn" data-act="edit" data-id="${m.id}">编辑</button>`,
    ];
    if (!m.is_default) {
      actions.push(`<button class="admin-btn danger" data-act="delete" data-id="${m.id}">删除</button>`);
    }
    tr.className = m.enabled ? "" : "disabled";
    tr.innerHTML = `
      <td class="mc-ellipsis"></td>
      <td>${m.provider}</td>
      <td class="mc-ellipsis"></td>
      <td class="mc-ellipsis"></td>
      <td class="num">${Math.round(m.max_context_size / 1024)}K</td>
      <td class="mc-ellipsis"></td>
      <td class="${m.enabled ? "status-active" : "status-disabled"}">${m.enabled ? "启用" : "停用"}</td>
      <td><div class="row-actions">${actions.join("")}</div></td>`;
    tr.cells[0].textContent = m.id;
    tr.cells[0].title = m.id;
    tr.cells[2].textContent = m.model;
    tr.cells[2].title = m.model;
    tr.cells[3].textContent = m.display_name;
    tr.cells[3].title = m.display_name;
    tr.cells[5].textContent = m.capabilities.join(", ") || "-";
    if (m.capabilities.length) tr.cells[5].title = m.capabilities.join(", ");
  }, "暂无模型");
}

export function fillMcModelForm(m) {
  if (!$("mc-m-provider")) return;
  if (m) $("mc-m-provider").value = m.provider;
  $("mc-m-model").value = m ? m.model : "";
  $("mc-m-display").value = m ? m.display_name : "";
  $("mc-m-context").value = m ? m.max_context_size : "";
  $("mc-m-output").value = m ? m.max_output_size : "";
  $("mc-m-caps").value = m ? m.capabilities.join(",") : "";
  $("mc-m-efforts").value = m ? m.support_efforts.join(",") : "";
  $("mc-m-effort").value = m ? (m.default_effort || "") : "";
  $("mc-m-reasoning").value = m ? (m.reasoning_key || "") : "";
  $("mc-m-enabled").checked = m ? !!m.enabled : true;
  $("mc-m-submit").textContent = m ? "保存修改" : "新增模型";
  $("mc-m-cancel").classList.toggle("hidden", !m);
  editingModelDefId = m ? m.id : null;
}

export async function onMcModelSubmit(e) {
  e.preventDefault();
  const body = {
    originalId: editingModelDefId,
    provider: $("mc-m-provider").value,
    model: $("mc-m-model").value.trim(),
    displayName: $("mc-m-display").value.trim(),
    maxContextSize: Number($("mc-m-context").value),
    maxOutputSize: Number($("mc-m-output").value || 0),
    capabilities: csvToList($("mc-m-caps").value),
    supportEfforts: csvToList($("mc-m-efforts").value),
    defaultEffort: $("mc-m-effort").value.trim(),
    reasoningKey: $("mc-m-reasoning").value.trim(),
    enabled: $("mc-m-enabled").checked,
  };
  if (!body.provider || !body.model || !body.displayName) {
    UI.toast("Provider / 实际模型名 / 展示名 必填", { type: "error" });
    return;
  }
  if (!Number.isFinite(body.maxContextSize) || body.maxContextSize < 1) {
    UI.toast("上下文窗口必须为正数", { type: "error" });
    return;
  }
  try {
    await api("/api/admin/model-config/models", { method: "POST", body });
    fillMcModelForm(null);
    UI.toast("模型已保存");
    await loadModelConfig();
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}

export async function onMcModelAction(btn) {
  const id = btn.dataset.id;
  try {
    if (btn.dataset.act === "edit") {
      fillMcModelForm(modelConfig.models.find((m) => m.id === id) || null);
      $("mc-m-model").focus();
    } else if (btn.dataset.act === "delete") {
      const ok = await UI.confirm({
        title: "删除模型",
        message: `确定删除模型「${id}」吗？\n存量会话若仍引用它将无法继续对话。`,
        okText: "删除",
        danger: true,
        submit: () => api("/api/admin/model-config/models/delete", { method: "POST", body: { modelId: id } }),
      });
      if (!ok) return;
      if (editingModelDefId === id) fillMcModelForm(null);
      UI.toast("模型已删除");
      await loadModelConfig();
    }
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}

export async function onMcDefaultChange() {
  const modelId = $("mc-default").value;
  if (!modelId || modelId === modelConfig.defaultModel) return;
  try {
    await api("/api/admin/model-config/default", { method: "POST", body: { modelId } });
    modelConfig.defaultModel = modelId;
    renderMcModels();
    UI.toast("默认模型已切换");
  } catch (err) {
    UI.toast(err.message, { type: "error" });
    await loadModelConfig();
  }
}
