/**
 * 自绘对话框与轻提示组件（dialog, confirm, prompt, toast）
 */

import { h } from "../utils/dom.js";
import { enhance, closePopover } from "./controls.js";

let seq = 0;

export function dialog(opts) {
  return new Promise((resolve) => {
    const fields = opts.fields || [];
    const prevFocus = document.activeElement;

    const overlay = h("div", "ui-dialog");
    const box = h("form", "ui-dialog-box");
    box.noValidate = true;
    box.tabIndex = -1;
    box.setAttribute("role", opts.danger ? "alertdialog" : "dialog");
    box.setAttribute("aria-modal", "true");

    const title = h("h3", "ui-dialog-title", opts.title || "");
    title.id = "ui-dialog-title-" + (++seq);
    box.setAttribute("aria-labelledby", title.id);
    box.appendChild(title);
    if (opts.message) box.appendChild(h("p", "ui-dialog-msg", opts.message));

    const inputs = {};
    for (const f of fields) {
      // 下拉增强后原生 select 被隐藏，label 点击会落空，故改用 div
      const wrap = h(f.type === "select" ? "div" : "label", "ui-field");
      if (f.label) wrap.appendChild(h("span", "ui-field-label", f.label));
      const control = h("span", "ui-control");
      let input;
      if (f.type === "select") {
        input = h("select", "ui-input");
        for (const o of f.options || []) {
          const opt = document.createElement("option");
          opt.value = o.value;
          opt.textContent = o.label;
          input.appendChild(opt);
        }
        if (f.value != null) input.value = String(f.value);
      } else {
        input = h("input", "ui-input");
        input.type = f.type || "text";
        input.spellcheck = false;
        if (f.placeholder) input.placeholder = f.placeholder;
        if (f.autocomplete) input.autocomplete = f.autocomplete;
        if (f.step != null) input.step = String(f.step);
        if (f.min != null) input.min = String(f.min);
        if (f.maxLength) input.maxLength = f.maxLength;
        if (f.inputMode) input.inputMode = f.inputMode;
      }
      input.name = f.name;
      if (f.value != null && f.type !== "select") input.value = String(f.value);
      if (f.disabled) input.disabled = true;
      control.appendChild(input);
      if (f.suffix) {
        control.classList.add("has-suffix");
        control.appendChild(h("span", "ui-suffix", f.suffix));
      }
      if (f.suffixBtn) {
        control.classList.add("has-suffix-btn");
        const btn = h("button", "ui-suffix-btn", f.suffixBtn.text || "");
        btn.type = "button";
        btn.addEventListener("click", () => f.suffixBtn.onClick(btn, input, inputs));
        control.appendChild(btn);
      }
      wrap.appendChild(control);
      if (f.hint) wrap.appendChild(h("span", "ui-field-hint", f.hint));
      box.appendChild(wrap);
      inputs[f.name] = input;
    }

    const error = h("div", "ui-dialog-error");
    error.setAttribute("role", "alert");
    box.appendChild(error);

    const actions = h("div", "ui-dialog-actions");
    const cancelBtn = h("button", "ui-btn", opts.cancelText || "取消");
    cancelBtn.type = "button";
    const okBtn = h("button", "ui-btn " + (opts.danger ? "ui-btn-danger" : "ui-btn-primary"), opts.okText || "确定");
    okBtn.type = "submit";
    actions.append(cancelBtn, okBtn);
    box.appendChild(actions);
    overlay.appendChild(box);

    let busy = false;
    const readValues = () => Object.fromEntries(Object.entries(inputs).map(([k, el]) => [k, el.value]));
    const showError = (msg, name) => {
      error.textContent = msg || "";
      for (const el of Object.values(inputs)) el.classList.remove("invalid");
      const target = name && inputs[name];
      if (target) {
        target.classList.add("invalid");
        target.focus();
        target.select?.();
      }
    };
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
      } else if (e.key === "Tab") {
        const items = [...box.querySelectorAll("input, select, button")].filter((el) => !el.disabled && el.tabIndex >= 0);
        if (!items.length) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && (document.activeElement === first || document.activeElement === box)) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    });

    let downOnBackdrop = false;
    overlay.addEventListener("mousedown", (e) => { downOnBackdrop = e.target === overlay; });
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay && downOnBackdrop && !busy) close(null);
    });

    cancelBtn.onclick = () => { if (!busy) close(null); };
    box.addEventListener("change", () => opts.onChange?.(readValues(), inputs));
    opts.onChange?.(readValues(), inputs);
    box.addEventListener("input", () => { if (error.textContent) showError(""); });
    box.addEventListener("submit", async (e) => {
      e.preventDefault();
      if (busy) return;
      const values = readValues();
      const invalid = opts.validate ? opts.validate(values) : null;
      if (invalid) {
        if (typeof invalid === "string") showError(invalid);
        else showError(invalid.message, invalid.name);
        return;
      }
      if (opts.submit) {
        setBusy(true);
        try {
          await opts.submit(values);
        } catch (err) {
          setBusy(false);
          showError((err && err.message) || String(err));
          return;
        }
      }
      close(values);
    });

    document.body.appendChild(overlay);
    enhance(box);
    const firstField = fields.find((f) => !f.disabled);
    const firstInput = firstField ? inputs[firstField.name] : null;
    if (firstInput) {
      firstInput.focus();
      if (firstInput.value) firstInput.select?.();
    } else {
      okBtn.focus();
    }
  });
}

export function confirm(opts) {
  return dialog({ ...opts, fields: [], validate: null }).then((r) => r !== null);
}

export function prompt(opts) {
  const field = {
    name: "value",
    label: opts.label,
    type: opts.type,
    value: opts.value,
    placeholder: opts.placeholder,
    hint: opts.hint,
    suffix: opts.suffix,
    autocomplete: opts.autocomplete,
    step: opts.step,
    min: opts.min,
    maxLength: opts.maxLength,
    inputMode: opts.inputMode,
  };
  return dialog({
    title: opts.title,
    message: opts.message,
    okText: opts.okText,
    cancelText: opts.cancelText,
    danger: opts.danger,
    fields: [field],
    validate: opts.validate ? (v) => opts.validate(v.value) : null,
    submit: opts.submit ? (v) => opts.submit(v.value) : null,
  }).then((r) => (r === null ? null : r.value));
}

export function toast(message, opts = {}) {
  let host = document.querySelector(".ui-toasts");
  if (!host) {
    host = h("div", "ui-toasts");
    host.setAttribute("role", "status");
    host.setAttribute("aria-live", "polite");
    document.body.appendChild(host);
  }
  const item = h("div", "ui-toast" + (opts.type === "error" ? " error" : ""), message);
  host.appendChild(item);
  setTimeout(() => {
    item.classList.add("leaving");
    setTimeout(() => item.remove(), 200);
  }, opts.duration || 2400);
}
