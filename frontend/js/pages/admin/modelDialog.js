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
  { value: "image_in", label: "图像" },
  { value: "native_search", label: "搜索", providerTypes: ["responses", "anthropic"] },
];

/** 默认上下文窗口大小（tokens） */
const DEFAULT_CONTEXT_SIZE = 262144;
/** 默认输出上限大小（tokens） */
const DEFAULT_OUTPUT_SIZE = 131072;

function field(labelText, control, hintText, isLabel) {
  const wrap = h(isLabel ? "label" : "div", "ui-field");
  wrap.appendChild(h("span", "ui-field-label", labelText));
  wrap.appendChild(control);
  if (hintText) wrap.appendChild(h("span", "ui-field-hint", hintText));
  return wrap;
}

export function openModelDialog({ mode, provider, providerType, model, thinkingEfforts, displayName = "", existing = null, onSaved }) {
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

    const choiceTag = (text, checked, group) => {
      const input = h("input");
      input.type = "checkbox";
      input.checked = checked;
      const label = h("label", "ui-choice-tag");
      label.append(input, h("span", "", text));
      group.appendChild(label);
      return input;
    };
    const capsBox = h("div", "ui-choice-tags");
    capsBox.setAttribute("role", "group");
    capsBox.setAttribute("aria-label", "能力支持");
    const capInputs = CAPABILITIES
      .filter((c) => !c.providerTypes || c.providerTypes.includes(providerType))
      .map((c) => ({
        value: c.value,
        input: choiceTag(c.label, isEdit && existing.capabilities.includes(c.value), capsBox),
      }));
    box.appendChild(field("能力支持", capsBox));

    const effortChoices = h("div", "ui-choice-tags");
    effortChoices.setAttribute("role", "group");
    effortChoices.setAttribute("aria-label", "思考挡位");
    const effortInputs = thinkingEfforts.map((level) => ({
      level,
      input: choiceTag(level, isEdit ? existing.support_efforts.includes(level) : ["low", "high", "xhigh"].includes(level), effortChoices),
    }));
    const choiceField = field("思考挡位", effortChoices);
    box.appendChild(choiceField);
    const effortSel = h("select", "ui-input");
    const effortField = field("默认思考强度", effortSel);
    box.appendChild(effortField);
    const selectedEfforts = () => effortInputs.filter((item) => item.input.checked).map((item) => item.level);
    const syncEffortOptions = (prefer = effortSel.value) => {
      const levels = selectedEfforts();
      effortSel.textContent = "";
      for (const level of levels) {
        const option = h("option", "", level);
        option.value = level;
        effortSel.appendChild(option);
      }
      if (!levels.length) {
        const option = h("option", "", "请先选择思考挡位");
        option.value = "";
        effortSel.appendChild(option);
      }
      effortSel.value = levels.includes(prefer) ? prefer : levels.includes("high") ? "high" : levels[0] || "";
      effortSel.disabled = !levels.length;
      effortSel.dispatchEvent(new Event("change"));
    };
    for (const { input } of effortInputs) input.addEventListener("change", () => syncEffortOptions());

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
      for (const el of [displayInput, ctxInput, outInput, effortSel]) el.classList.remove("invalid");
      if (target) {
        target.classList.add("invalid");
        target.focus();
      }
    };

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
      const thinkingEnabled = capInputs.find((c) => c.value === "thinking").input.checked;
      const efforts = selectedEfforts();
      if (thinkingEnabled && (!efforts.length || !efforts.includes(effortSel.value))) {
        showError("请至少选择一个思考挡位，并选择默认挡位", effortSel);
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
        supportEfforts: thinkingEnabled ? efforts : [],
        defaultEffort: thinkingEnabled ? effortSel.value : "",
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

    const thinkingInput = capInputs.find((c) => c.value === "thinking").input;
    const syncThinking = () => {
      choiceField.classList.toggle("hidden", !thinkingInput.checked);
      effortField.classList.toggle("hidden", !thinkingInput.checked);
    };
    thinkingInput.addEventListener("change", () => {
      syncThinking();
      syncEffortOptions();
    });
    syncThinking();
    syncEffortOptions(isEdit ? existing.default_effort : "high");
    document.body.appendChild(overlay);
    UI.enhance(box);
    displayInput.focus();
    if (displayInput.value) displayInput.select();
  });
}
