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
        input = h(f.type === "textarea" ? "textarea" : "input", "ui-input");
        if (f.type === "textarea") input.rows = f.rows || 4;
        else input.type = f.type || "text";
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
        const items = [...box.querySelectorAll("input, select, textarea, button")].filter((el) => !el.disabled && el.tabIndex >= 0);
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
      const focusTarget = firstInput.closest(".ui-select")?.querySelector(".ui-select-btn") || firstInput;
      focusTarget.focus();
      if (focusTarget === firstInput && firstInput.value) firstInput.select?.();
    } else if (window.matchMedia("(pointer: coarse)").matches) {
      box.focus();
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

const TOAST_VISIBLE_COUNT = 3;
const TOAST_GAP = 14;
const TOAST_DURATION = 5000;
const TOAST_UNMOUNT_DELAY = 200;
const TOAST_TYPES = new Set(["default", "success", "error", "warning", "info", "loading"]);
const TOAST_ICONS = {
  success: '<circle cx="12" cy="12" r="9"/><path d="M8.5 12.2l2.4 2.4 4.6-4.9"/>',
  error: '<circle cx="12" cy="12" r="9"/><path d="M9.2 9.2l5.6 5.6M14.8 9.2l-5.6 5.6"/>',
  warning: '<path d="M10.5 3.9L2.9 17.1a1.7 1.7 0 001.5 2.6h15.2a1.7 1.7 0 001.5-2.6L13.5 3.9a1.7 1.7 0 00-3 0z"/><path d="M12 9v4.2M12 16.4v.2"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11.2v4.6M12 7.8v.2"/>',
  loading: '<path d="M12 3a9 9 0 019 9"/>',
};

let toastHost = null;
let toastExpanded = false;
const toastRecords = [];

function getToastHost() {
  if (toastHost?.isConnected) return toastHost;
  toastHost = h("ol", "ui-toasts");
  toastHost.setAttribute("aria-label", "通知");
  toastHost.setAttribute("aria-live", "polite");
  toastHost.setAttribute("aria-relevant", "additions text");
  toastHost.setAttribute("aria-atomic", "false");

  const collapseIfIdle = () => requestAnimationFrame(() => {
    if (!toastHost.matches(":hover") && !toastHost.contains(document.activeElement)) {
      setToastExpanded(false);
    }
  });
  toastHost.addEventListener("mouseenter", () => setToastExpanded(true));
  toastHost.addEventListener("mouseleave", collapseIfIdle);
  toastHost.addEventListener("focusin", () => setToastExpanded(true));
  toastHost.addEventListener("focusout", collapseIfIdle);
  toastHost.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const item = event.target.closest(".ui-toast");
    const record = toastRecords.find((entry) => entry.el === item);
    if (!record) return;
    event.preventDefault();
    event.stopPropagation();
    dismissToast(record.id);
  });
  document.body.appendChild(toastHost);
  return toastHost;
}

function syncToasts() {
  if (!toastHost) return;
  let offset = 0;
  toastRecords.forEach((record, index) => {
    record.offset = offset;
    const item = record.el;
    item.style.setProperty("--toasts-before", index);
    item.style.setProperty("--z-index", toastRecords.length - index);
    item.style.setProperty("--offset", `${offset}px`);
    item.style.setProperty("--initial-height", `${record.height}px`);
    const visible = toastExpanded || index < TOAST_VISIBLE_COUNT;
    item.dataset.front = String(index === 0);
    item.dataset.expanded = String(toastExpanded);
    item.dataset.visible = String(visible);
    item.tabIndex = visible ? 0 : -1;
    const close = item.querySelector(".ui-toast-close");
    if (close) close.tabIndex = visible ? 0 : -1;
    offset += record.height + TOAST_GAP;
  });
  toastHost.style.setProperty("--front-toast-height", `${toastRecords[0]?.height || 0}px`);
}

function measureToast(record) {
  const previous = record.el.style.height;
  record.el.style.height = "auto";
  record.height = record.el.getBoundingClientRect().height;
  record.el.style.height = previous;
}

function pauseToasts() {
  for (const record of toastRecords) {
    if (record.timer === null) continue;
    clearTimeout(record.timer);
    record.timer = null;
    record.remaining = Math.max(0, record.remaining - (Date.now() - record.startedAt));
  }
}

function resumeToast(record) {
  if (record.timer !== null || !Number.isFinite(record.remaining)) return;
  record.startedAt = Date.now();
  record.timer = setTimeout(() => dismissToast(record.id), record.remaining);
}

function resumeToasts() {
  for (const record of toastRecords) resumeToast(record);
}

function setToastExpanded(value) {
  if (toastExpanded === value) return;
  toastExpanded = value;
  syncToasts();
  if (value) pauseToasts();
  else if (!document.hidden) resumeToasts();
}

export function dismissToast(id) {
  const index = toastRecords.findIndex((record) => record.id === id);
  if (index === -1) return;
  const [record] = toastRecords.splice(index, 1);
  record.observer?.disconnect();
  clearTimeout(record.timer);
  record.timer = null;
  record.el.style.setProperty("--offset", `${record.offset}px`);
  record.el.dataset.removed = "true";
  record.el.tabIndex = -1;
  const close = record.el.querySelector(".ui-toast-close");
  if (close) close.tabIndex = -1;
  if (!toastRecords.length) toastExpanded = false;
  syncToasts();
  setTimeout(() => {
    record.el.remove();
    syncToasts();
  }, TOAST_UNMOUNT_DELAY);
}

export function dismissAllToasts() {
  for (const record of [...toastRecords]) dismissToast(record.id);
}

export function toast(message, opts = {}) {
  const type = TOAST_TYPES.has(opts.type) ? opts.type : "success";
  const duration = opts.duration ?? (type === "loading" ? Infinity : TOAST_DURATION);
  const record = {
    id: ++seq,
    remaining: duration,
    timer: null,
    startedAt: 0,
    height: 0,
    offset: 0,
    el: null,
    observer: null,
  };
  const item = h("li", "ui-toast");
  item.tabIndex = 0;
  item.dataset.type = type;
  item.dataset.mounted = "false";
  item.dataset.removed = "false";
  item.dataset.front = "false";
  item.dataset.expanded = "false";
  item.dataset.visible = "true";
  item.setAttribute("role", type === "error" ? "alert" : "status");
  item.setAttribute("aria-atomic", "true");
  record.el = item;

  if (type !== "loading") {
    const close = h("button", "ui-toast-close");
    close.type = "button";
    close.setAttribute("aria-label", "关闭通知");
    close.innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
    close.addEventListener("click", () => dismissToast(record.id));
    item.appendChild(close);
  }

  if (TOAST_ICONS[type]) {
    const icon = h("span", "ui-toast-icon");
    icon.setAttribute("aria-hidden", "true");
    icon.innerHTML = `<svg viewBox="0 0 24 24">${TOAST_ICONS[type]}</svg>`;
    item.appendChild(icon);
  }

  const content = h("div", "ui-toast-content");
  content.appendChild(h("div", "ui-toast-title", message));
  if (opts.description) content.appendChild(h("div", "ui-toast-description", opts.description));
  item.appendChild(content);

  const host = getToastHost();
  host.appendChild(item);
  toastRecords.unshift(record);
  measureToast(record);
  syncToasts();

  if (typeof ResizeObserver === "function") {
    record.observer = new ResizeObserver(() => {
      measureToast(record);
      syncToasts();
    });
    record.observer.observe(content);
  }

  requestAnimationFrame(() => {
    requestAnimationFrame(() => { item.dataset.mounted = "true"; });
  });
  if (!toastExpanded && !document.hidden) resumeToast(record);
  return record.id;
}

document.addEventListener("visibilitychange", () => {
  if (document.hidden) pauseToasts();
  else if (!toastExpanded) resumeToasts();
});
