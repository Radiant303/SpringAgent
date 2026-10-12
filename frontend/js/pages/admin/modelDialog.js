/**
 * 模型添加与编辑弹窗。
 *
 * @author hanbing
 * @since 2026-10-13
 */

import { h } from "../../utils/dom.js";
import { UI } from "../../components/ui.js";
import { api } from "../../api/client.js";
import { closePopover } from "../../components/controls.js";

const CAPABILITIES = [
  { value: "thinking", label: "思考" },
  { value: "always_thinking", label: "总思考" },
  { value: "image_in", label: "图像" },
  { value: "audio_in", label: "音频" },
  { value: "video_in", label: "视频" },
];

const DEFAULT_EFFORTS = ["low", "high", "max"];
/** 单个模型支持的思考挡位数量上限 */
const MAX_EFFORTS = 8;
/** 单个思考挡位字符长度上限 */
const MAX_EFFORT_LEN = 16;
/** 默认上下文窗口大小（tokens） */
const DEFAULT_CONTEXT_SIZE = 131072;
/** 默认输出上限大小（tokens） */
const DEFAULT_OUTPUT_SIZE = 8192;

function field(labelText, control, hintText, isLabel) {
  const wrap = h(isLabel ? "label" : "div", "ui-field");
  wrap.appendChild(h("span", "ui-field-label", labelText));
  wrap.appendChild(control);
  if (hintText) wrap.appendChild(h("span", "ui-field-hint", hintText));
  return wrap;
}

/**
 * 打开模型配置弹窗
 *
 * @param {object} opts
 * @param {"add"|"edit"} opts.mode 弹窗模式
 * @param {string} opts.provider 所属 Provider 名
 * @param {string} opts.model 实际模型名
 * @param {string} [opts.displayName] 预填展示名
 * @param {object|null} [opts.existing] 编辑时的模型行
 * @param {() => void} [opts.onSaved] 保存成功回调
 */
export function openModelDialog({ mode, provider, model, displayName = "", existing = null, onSaved }) {
  return new Promise((resolve) => {
    const prevFocus = document.activeElement;
    const isEdit = mode === "edit" && existing;

    const overlay = h("div", "ui-dialog");
    const box = h("form", "ui-dialog-box mc-model-dlg");
    box.noValidate = true;
    box.tabIndex = -1;
    box.setAttribute("role", "dialog");
    box.setAttribute("aria-modal", "true");
    box.appendChild(h("h3", "ui-dialog-title", isEdit ? "编辑模型" : "添加模型"));

    const row1 = h("div", "md-grid-2");
    const idStatic = h("div", "md-static", model);
    idStatic.title = model;
    const providerStatic = h("div", "md-static", provider);
    providerStatic.title = provider;
    row1.append(
      field("模型 ID", idStatic),
      field("Provider", providerStatic)
    );
    box.appendChild(row1);

    const displayInput = h("input", "ui-input");
    displayInput.type = "text";
    displayInput.spellcheck = false;
    displayInput.maxLength = 64;
    displayInput.placeholder = "留空则与模型 ID 相同";
    displayInput.value = isEdit ? existing.display_name : displayName;
    box.appendChild(field("展示名称", displayInput, null, true));

    const row3 = h("div", "md-grid-2");
    const ctxInput = h("input", "ui-input");
    ctxInput.type = "number";
    ctxInput.min = "1";
    ctxInput.step = "1";
    ctxInput.placeholder = String(DEFAULT_CONTEXT_SIZE);
    const ctxVal = isEdit && existing && (existing.max_context_size ?? existing.maxContextSize) != null
      ? (existing.max_context_size ?? existing.maxContextSize)
      : DEFAULT_CONTEXT_SIZE;
    ctxInput.value = String(ctxVal);

    const outInput = h("input", "ui-input");
    outInput.type = "number";
    outInput.min = "0";
    outInput.step = "1";
    outInput.placeholder = String(DEFAULT_OUTPUT_SIZE);
    const outVal = isEdit && existing && (existing.max_output_size ?? existing.maxOutputSize) != null
      ? (existing.max_output_size ?? existing.maxOutputSize)
      : DEFAULT_OUTPUT_SIZE;
    outInput.value = String(outVal);

    row3.append(
      field("上下文窗口（tokens）", ctxInput, null, true),
      field("输出上限（tokens）", outInput, "0 = 不限制", true)
    );
    box.appendChild(row3);

    const capsBox = h("div", "md-caps");
    const capInputs = CAPABILITIES.map((c) => {
      const input = h("input");
      input.type = "checkbox";
      input.className = "switch";
      input.checked = isEdit && existing.capabilities.includes(c.value);
      const label = h("label", "md-cap");
      label.append(input, h("span", "", c.label));
      capsBox.appendChild(label);
      return { value: c.value, input };
    });
    box.appendChild(field("能力支持", capsBox));

    let efforts = isEdit ? [...(existing.support_efforts || [])] : [...DEFAULT_EFFORTS];
    const tagsBox = h("div", "md-tags");
    const tagInput = h("input", "ui-input");
    tagInput.type = "text";
    tagInput.spellcheck = false;
    tagInput.maxLength = MAX_EFFORT_LEN;
    tagInput.placeholder = "输入挡位，回车或点添加";
    const tagAddBtn = h("button", "ui-suffix-btn", "添加");
    tagAddBtn.type = "button";
    const tagControl = h("span", "ui-control has-suffix-btn");
    tagControl.append(tagInput, tagAddBtn);
    const tagField = field("思考挡位", h("div", "md-efforts"));
    tagField.querySelector(".md-efforts").append(tagsBox, tagControl);
    box.appendChild(tagField);

    const effortSel = h("select", "ui-input");
    const effortField = field("默认挡位", effortSel);
    box.appendChild(effortField);

    const error = h("div", "ui-dialog-error");
    error.setAttribute("role", "alert");
    box.appendChild(error);

    const footer = h("div", "mc-dialog-footer");
    const enabledLabel = h("label", "md-switch-row");
    const enabledSw = h("input");
    enabledSw.type = "checkbox";
    enabledSw.className = "switch";
    enabledSw.checked = isEdit ? !!existing.enabled : true;
    enabledLabel.append(enabledSw, h("span", "", "是否启用"));

    const actions = h("div", "ui-dialog-actions");
    const cancelBtn = h("button", "ui-btn", "取消");
    cancelBtn.type = "button";
    const okBtn = h("button", "ui-btn ui-btn-primary", isEdit ? "保存" : "添加");
    okBtn.type = "submit";
    actions.append(cancelBtn, okBtn);

    footer.append(enabledLabel, actions);
    box.appendChild(footer);
    overlay.appendChild(box);

    const showError = (msg, target) => {
      error.textContent = msg || "";
      for (const el of [displayInput, ctxInput, outInput, tagInput]) el.classList.remove("invalid");
      if (target) {
        target.classList.add("invalid");
        target.focus();
      }
    };

    const renderTags = () => {
      tagsBox.textContent = "";
      for (const t of efforts) {
        const tag = h("span", "md-tag");
        tag.append(h("span", "", t));
        const x = h("button", "", "×");
        x.type = "button";
        x.setAttribute("aria-label", `删除 ${t}`);
        x.addEventListener("click", () => {
          efforts = efforts.filter((e) => e !== t);
          syncEffortOptions();
          renderTags();
        });
        tag.appendChild(x);
        tagsBox.appendChild(tag);
      }
    };

    const syncEffortOptions = (prefer) => {
      const prev = prefer ?? effortSel.value;
      effortSel.textContent = "";
      if (efforts.length === 0) {
        const opt = document.createElement("option");
        opt.value = "";
        opt.textContent = "无（未配置思考挡位）";
        effortSel.appendChild(opt);
        effortSel.value = "";
        effortSel.disabled = true;
      } else {
        effortSel.disabled = false;
        for (const t of efforts) {
          const opt = document.createElement("option");
          opt.value = t;
          opt.textContent = t;
          effortSel.appendChild(opt);
        }
        if (efforts.includes(prev)) {
          effortSel.value = prev;
        } else if (efforts.includes("high")) {
          effortSel.value = "high";
        } else {
          effortSel.value = efforts[0];
        }
      }
      effortSel.dispatchEvent(new Event("change"));
    };

    const addEffort = () => {
      const v = tagInput.value.trim();
      if (!v) return;
      if (efforts.includes(v)) {
        showError(`挡位 ${v} 已存在`, tagInput);
        return;
      }
      if (efforts.length >= MAX_EFFORTS) {
        showError(`挡位最多 ${MAX_EFFORTS} 个`, tagInput);
        return;
      }
      const wasEmpty = efforts.length === 0;
      efforts.push(v);
      tagInput.value = "";
      showError("");
      syncEffortOptions(wasEmpty ? v : undefined);
      renderTags();
      tagInput.focus();
    };
    tagAddBtn.addEventListener("click", addEffort);
    tagInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        addEffort();
      }
    });

    let busy = false;
    const setBusy = (on) => {
      busy = on;
      overlay.classList.toggle("is-busy", on);
      okBtn.disabled = on;
      cancelBtn.disabled = on;
    };
    const close = (result) => {
      closePopover();
      overlay.remove();
      if (prevFocus && typeof prevFocus.focus === "function" && document.contains(prevFocus)) prevFocus.focus();
      resolve(result);
    };

    overlay.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "Escape") {
        e.preventDefault();
        if (!busy) close(null);
      }
    });
    let downOnBackdrop = false;
    overlay.addEventListener("mousedown", (e) => { downOnBackdrop = e.target === overlay; });
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay && downOnBackdrop && !busy) close(null);
    });

    cancelBtn.onclick = () => { if (!busy) close(null); };
    box.addEventListener("input", () => { if (error.textContent) showError(""); });
    box.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (busy) return;
      const ctx = Number(ctxInput.value);
      if (!Number.isInteger(ctx) || ctx < 1) {
        showError("上下文窗口必须为正整数", ctxInput);
        return;
      }
      const out = Number(outInput.value);
      if (!Number.isInteger(out) || out < 0) {
        showError("输出上限必须为非负整数", outInput);
        return;
      }
      const body = {
        originalId: isEdit ? existing.id : null,
        provider,
        model,
        displayName: displayInput.value.trim() || model,
        maxContextSize: ctx,
        maxOutputSize: out,
        capabilities: capInputs.filter((c) => c.input.checked).map((c) => c.value),
        supportEfforts: efforts,
        defaultEffort: efforts.length ? effortSel.value : "",
        enabled: enabledSw.checked,
      };
      setBusy(true);
      try {
        await api("/api/admin/model-config/models", { method: "POST", body });
      } catch (err) {
        setBusy(false);
        showError((err && err.message) || String(err));
        return;
      }
      close(true);
      if (onSaved) onSaved();
    });

    renderTags();
    syncEffortOptions(isEdit ? existing.default_effort : "high");
    document.body.appendChild(overlay);
    UI.enhance(box);
    displayInput.focus();
    if (displayInput.value) displayInput.select();
  });
}
