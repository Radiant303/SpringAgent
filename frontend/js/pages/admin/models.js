/**
 * 模型资费配置（Rate Cards）
 */

import { $ } from "../../utils/dom.js";
import { fmtPoints, fmtTime } from "../../utils/format.js";
import { getRole } from "../../api/auth.js";
import { api } from "../../api/client.js";
import { UI } from "../../components/ui.js";

export let models = [];
let editingModelId = null;

let modelDefinitions = [];

export async function loadModels() {
  models = await UI.loadTable("model-tbody", async () => {
    const [cards, config] = await Promise.all([
      api("/api/admin/rate-cards"),
      getRole() === "owner" ? api("/api/admin/model-config") : Promise.resolve(null),
    ]);
    modelDefinitions = config?.models || [];
    return cards;
  }) || [];
  renderModels();
  fillModelForm(models.find((m) => m.model_id === editingModelId) || null);
}

function fillModelOptions(value) {
  const select = $("model-id");
  select.replaceChildren(new Option("选择模型 ID", ""));
  const configuredIds = new Set(models.map((card) => card.model_id));
  const choices = new Map(modelDefinitions
    .filter((model) => model.id !== "__default__"
      && (!configuredIds.has(model.id) || model.id === value))
    .map((model) => [model.id, model.id]));
  if (value) choices.set(value, value);
  for (const [id] of choices) {
    select.add(new Option(id === "__default__" ? "兜底资费" : id, id));
  }
  select.value = value || "";
  select.disabled = !!value;
  UI.enhance();
}

export function renderModels() {
  const myRole = getRole();
  UI.renderRows("model-tbody", models, (tr, m) => {
    const isDefault = m.model_id === "__default__";
    const actions = [];
    if (myRole === "owner") {
      actions.push(`<button class="admin-btn" data-act="edit" data-id="${m.model_id}">编辑</button>`);
      if (!isDefault) {
        actions.push(`<button class="admin-btn danger" data-act="delete" data-id="${m.model_id}" data-name="${m.model_id}">删除</button>`);
      }
    }
    tr.className = m.enabled ? "" : "disabled";
    tr.innerHTML = `
      <td>${m.model_id}${isDefault ? ' <span class="settings-hint">(兜底卡)</span>' : ""}</td>
      <td class="num">${fmtPoints(m.input_points)}</td>
      <td class="num">${fmtPoints(m.cache_read_points)}</td>
      <td class="num">${fmtPoints(m.cache_write_points)}</td>
      <td class="num">${fmtPoints(m.output_points)}</td>
      <td class="${m.enabled ? "status-active" : "status-disabled"}">${m.enabled ? "启用" : "停用"}</td>
      <td>${fmtTime(m.updated_at)}</td>
      <td><div class="row-actions">${actions.join("")}</div></td>`;
  }, "暂无资费卡");
}

export function fillModelForm(m) {
  if (!$("model-id")) return;
  fillModelOptions(m?.model_id);
  $("model-input").value = m ? Number(m.input_points) : "";
  $("model-cache-read").value = m ? Number(m.cache_read_points) : "";
  $("model-cache-write").value = m ? Number(m.cache_write_points) : "";
  $("model-output").value = m ? Number(m.output_points) : "";
  $("model-enabled").checked = m ? !!m.enabled : true;
  $("model-enabled").disabled = m?.model_id === "__default__";
  $("model-submit").textContent = m ? "保存修改" : "保存资费卡";
  $("model-cancel").classList.toggle("hidden", !m);
  editingModelId = m?.model_id || null;
}

export async function onModelFormSubmit(e) {
  e.preventDefault();
  const body = {
    modelId: $("model-id").value,
    inputPoints: Number($("model-input").value),
    cacheReadPoints: Number($("model-cache-read").value),
    cacheWritePoints: Number($("model-cache-write").value),
    outputPoints: Number($("model-output").value),
    enabled: $("model-enabled").checked,
  };
  if (!body.modelId) { UI.toast("请选择模型 ID", { type: "error" }); return; }
  for (const key of ["inputPoints", "cacheReadPoints", "cacheWritePoints", "outputPoints"]) {
    if (!Number.isFinite(body[key]) || body[key] < 0) {
      UI.toast("费率必须是非负数字", { type: "error" });
      return;
    }
  }
  const button = $("model-submit");
  if (button.disabled) return;
  button.disabled = true;
  try {
    await api("/api/admin/rate-cards", { method: "PUT", body });
    fillModelForm(null);
    UI.toast("资费卡已保存");
    await loadModels();
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  } finally {
    button.disabled = false;
  }
}

export async function onModelAction(btn) {
  const modelId = btn.dataset.id;
  try {
    if (btn.dataset.act === "edit") {
      fillModelForm(models.find((m) => m.model_id === modelId) || null);
      $("model-input").focus();
    } else if (btn.dataset.act === "delete") {
      const ok = await UI.confirm({
        title: "删除资费卡",
        message: `确定删除「${modelId}」吗？\n删除后该模型按兜底卡计费。`,
        okText: "删除",
        danger: true,
        submit: () => api("/api/admin/rate-cards/delete", { method: "POST", body: { modelId } }),
      });
      if (!ok) return;
      if (editingModelId === modelId) fillModelForm(null);
      UI.toast("资费卡已删除");
      await loadModels();
    }
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}
