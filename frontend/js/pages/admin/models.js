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

export async function loadModels() {
  models = await UI.loadTable("model-tbody", () => api("/api/admin/rate-cards")) || [];
  renderModels();
}

export function renderModels() {
  const myRole = getRole();
  UI.renderRows("model-tbody", models, (tr, m) => {
    const isDefault = m.model_name === "default";
    const actions = [];
    if (myRole === "owner") {
      actions.push(`<button class="admin-btn" data-act="edit" data-id="${m.id}">编辑</button>`);
      if (!isDefault) {
        actions.push(`<button class="admin-btn danger" data-act="delete" data-id="${m.id}" data-name="${m.model_name}">删除</button>`);
      }
    }
    tr.className = m.enabled ? "" : "disabled";
    tr.innerHTML = `
      <td>${m.model_name}${isDefault ? ' <span class="settings-hint">(兜底卡)</span>' : ""}</td>
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
  if (!$("model-name")) return;
  $("model-name").value = m ? m.model_name : "";
  $("model-input").value = m ? Number(m.input_points) : "";
  $("model-cache-read").value = m ? Number(m.cache_read_points) : "";
  $("model-cache-write").value = m ? Number(m.cache_write_points) : "";
  $("model-output").value = m ? Number(m.output_points) : "";
  $("model-enabled").checked = m ? !!m.enabled : true;
  $("model-submit").textContent = m ? "保存修改" : "新增资费卡";
  $("model-cancel").classList.toggle("hidden", !m);
  editingModelId = m ? m.id : null;
}

export async function onModelFormSubmit(e) {
  e.preventDefault();
  const body = {
    modelName: $("model-name").value.trim(),
    inputPoints: Number($("model-input").value),
    cacheReadPoints: Number($("model-cache-read").value),
    cacheWritePoints: Number($("model-cache-write").value),
    outputPoints: Number($("model-output").value),
    enabled: $("model-enabled").checked,
  };
  if (!body.modelName) { UI.toast("模型名不能为空", { type: "error" }); return; }
  for (const key of ["inputPoints", "cacheReadPoints", "cacheWritePoints", "outputPoints"]) {
    if (!Number.isFinite(body[key]) || body[key] < 0) {
      UI.toast("费率必须是非负数字", { type: "error" });
      return;
    }
  }
  try {
    const editing = !!editingModelId;
    if (editing) {
      await api(`/api/admin/rate-cards/${editingModelId}`, { method: "POST", body });
    } else {
      await api("/api/admin/rate-cards", { method: "POST", body });
    }
    fillModelForm(null);
    UI.toast(editing ? "资费卡已更新" : "资费卡已新增");
    await loadModels();
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}

export async function onModelAction(btn) {
  const id = btn.dataset.id;
  try {
    if (btn.dataset.act === "edit") {
      fillModelForm(models.find((m) => m.id === id) || null);
      $("model-name").focus();
    } else if (btn.dataset.act === "delete") {
      const ok = await UI.confirm({
        title: "删除资费卡",
        message: `确定删除「${btn.dataset.name}」吗？\n删除后该模型按兜底卡计费。`,
        okText: "删除",
        danger: true,
        submit: () => api(`/api/admin/rate-cards/${id}/delete`, { method: "POST" }),
      });
      if (!ok) return;
      if (editingModelId === id) fillModelForm(null);
      UI.toast("资费卡已删除");
      await loadModels();
    }
  } catch (err) {
    UI.toast(err.message, { type: "error" });
  }
}
