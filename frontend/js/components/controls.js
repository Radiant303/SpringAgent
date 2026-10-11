/**
 * 自绘表单增强控件（下拉框与日期选择器）
 */

import { h, $, icon } from "../utils/dom.js";

let seq = 0;
let currentPop = null;

const resolve = (x) => (typeof x === "string" ? $(x) : x);

/** 在 anchor 下方打开浮层（下方放不下时翻到上方） */
export function openPopover(anchor, content, onClose) {
  closePopover();
  const pop = h("div", "ui-popover");
  pop.appendChild(content);
  document.body.appendChild(pop);

  const r = anchor.getBoundingClientRect();
  const gap = 6;
  const margin = 8;
  pop.style.minWidth = r.width + "px";
  const w = pop.offsetWidth;
  const ht = pop.offsetHeight;
  let top = r.bottom + gap;
  if (top + ht > innerHeight - margin && r.top - gap - ht >= margin) {
    top = r.top - gap - ht;
    pop.classList.add("above");
  }
  pop.style.top = top + "px";
  pop.style.left = Math.max(margin, Math.min(r.left, innerWidth - w - margin)) + "px";

  const onDown = (e) => {
    if (!pop.contains(e.target) && !anchor.contains(e.target)) closePopover();
  };
  const onScroll = (e) => {
    if (!pop.contains(e.target)) closePopover();
  };
  const onResize = () => closePopover();
  document.addEventListener("pointerdown", onDown, true);
  document.addEventListener("scroll", onScroll, true);
  window.addEventListener("resize", onResize);

  currentPop = {
    pop,
    close() {
      document.removeEventListener("pointerdown", onDown, true);
      document.removeEventListener("scroll", onScroll, true);
      window.removeEventListener("resize", onResize);
      pop.remove();
      if (onClose) onClose();
    },
  };
  return pop;
}

export function closePopover() {
  const p = currentPop;
  currentPop = null;
  if (p) p.close();
}

function watchValue(el, proto, onSet) {
  const desc = Object.getOwnPropertyDescriptor(proto, "value");
  Object.defineProperty(el, "value", {
    configurable: true,
    get() { return desc.get.call(this); },
    set(v) { desc.set.call(this, v); onSet(); },
  });
}

function wrapNative(el, wrapClass, btn) {
  const wrap = h("span", wrapClass);
  el.replaceWith(wrap);
  wrap.append(el, btn);
  el.classList.add("ui-native");
  el.tabIndex = -1;
  el.setAttribute("aria-hidden", "true");
  if (el.title) { btn.title = el.title; btn.setAttribute("aria-label", el.title); }
  return wrap;
}

function emitChange(el) {
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

/* ================= 下拉框 ================= */

export function enhanceSelect(select) {
  if (select._uiEnhanced || select.multiple || select.size > 1) return;
  select._uiEnhanced = true;

  const btn = h("button", "ui-select-btn");
  btn.type = "button";
  btn.setAttribute("aria-haspopup", "listbox");
  btn.setAttribute("aria-expanded", "false");
  const label = h("span", "ui-select-label");
  btn.append(label, icon("caret", "ui-select-caret"));
  wrapNative(select, "ui-select", btn);

  const sync = () => {
    const opt = select.selectedOptions[0];
    label.textContent = opt ? opt.textContent : (select.dataset.placeholder || "");
    btn.disabled = select.disabled;
  };
  watchValue(select, HTMLSelectElement.prototype, sync);
  new MutationObserver(sync).observe(select, { childList: true, subtree: true, characterData: true, attributes: true });
  select.addEventListener("change", sync);
  sync();

  const choose = (i) => {
    const changed = i !== select.selectedIndex;
    select.selectedIndex = i;
    sync();
    closePopover();
    btn.focus();
    if (changed) emitChange(select);
  };

  const open = () => {
    const list = h("div", "ui-listbox");
    list.setAttribute("role", "listbox");
    list.tabIndex = -1;
    const opts = [...select.options];
    const items = [];
    let active = Math.max(0, select.selectedIndex);
    if (!opts[active] || opts[active].disabled) {
      const first = opts.findIndex((o) => !o.disabled);
      active = first >= 0 ? first : 0;
    }

    const setActive = (i, scroll) => {
      items[active]?.classList.remove("active");
      active = i;
      const item = items[active];
      if (!item) return;
      item.classList.add("active");
      list.setAttribute("aria-activedescendant", item.id);
      if (scroll) item.scrollIntoView({ block: "nearest" });
    };
    const step = (from, dir) => {
      for (let i = from + dir; i >= 0 && i < opts.length; i += dir) if (!opts[i].disabled) return i;
      return from;
    };

    opts.forEach((o, i) => {
      const item = h("div", "ui-option");
      item.id = "ui-option-" + (++seq);
      item.setAttribute("role", "option");
      item.setAttribute("aria-selected", String(i === select.selectedIndex));
      if (i === select.selectedIndex) item.classList.add("selected");
      if (o.disabled) { item.classList.add("disabled"); item.setAttribute("aria-disabled", "true"); }
      const check = h("span", "ui-option-check");
      if (i === select.selectedIndex) check.appendChild(icon("check"));
      item.append(check, h("span", "ui-option-label", o.textContent));
      item.addEventListener("mousemove", () => { if (!o.disabled && active !== i) setActive(i); });
      item.addEventListener("click", () => { if (!o.disabled) choose(i); });
      items.push(item);
      list.appendChild(item);
    });

    let typed = "";
    let typedTimer = null;
    list.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        setActive(step(active, e.key === "ArrowDown" ? 1 : -1), true);
      } else if (e.key === "Home" || e.key === "End") {
        e.preventDefault();
        setActive(e.key === "Home" ? step(-1, 1) : step(opts.length, -1), true);
      } else if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        if (!opts[active]?.disabled) choose(active);
      } else if (e.key === "Escape" || e.key === "Tab") {
        e.preventDefault();
        closePopover();
        btn.focus();
      } else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
        typed += e.key.toLowerCase();
        clearTimeout(typedTimer);
        typedTimer = setTimeout(() => { typed = ""; }, 600);
        const after = opts.findIndex((o, i) => !o.disabled && i > active && o.textContent.toLowerCase().startsWith(typed));
        const fromTop = opts.findIndex((o) => !o.disabled && o.textContent.toLowerCase().startsWith(typed));
        const target = after >= 0 ? after : fromTop;
        if (target >= 0) setActive(target, true);
      }
    });

    btn.setAttribute("aria-expanded", "true");
    openPopover(btn, list, () => btn.setAttribute("aria-expanded", "false"));
    setActive(active, true);
    list.focus();
  };

  btn.addEventListener("click", () => {
    if (btn.getAttribute("aria-expanded") === "true") closePopover();
    else open();
  });
  btn.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown" || e.key === "ArrowUp") { e.preventDefault(); open(); }
  });
}

/* ================= 日期框 ================= */

const WEEKDAYS = ["一", "二", "三", "四", "五", "六", "日"];
const pad2 = (n) => String(n).padStart(2, "0");
const toISO = (d) => `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
const fromISO = (s) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || "");
  return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null;
};
const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
const addMonths = (d, n) => {
  const t = new Date(d.getFullYear(), d.getMonth() + n, 1);
  const last = new Date(t.getFullYear(), t.getMonth() + 1, 0).getDate();
  return new Date(t.getFullYear(), t.getMonth(), Math.min(d.getDate(), last));
};

export function enhanceDate(input) {
  if (input._uiEnhanced) return;
  input._uiEnhanced = true;

  const btn = h("button", "ui-date-btn");
  btn.type = "button";
  btn.setAttribute("aria-haspopup", "dialog");
  btn.setAttribute("aria-expanded", "false");
  const label = h("span", "ui-date-label");
  btn.append(label, icon("calendar", "ui-date-icon"));
  wrapNative(input, "ui-date", btn);

  const sync = () => {
    const has = !!fromISO(input.value);
    label.textContent = has ? input.value : (input.dataset.placeholder || "选择日期");
    btn.classList.toggle("has-value", has);
    btn.disabled = input.disabled;
  };
  watchValue(input, HTMLInputElement.prototype, sync);
  input.addEventListener("change", sync);
  sync();

  const allowed = (iso) => (!input.min || iso >= input.min) && (!input.max || iso <= input.max);

  const choose = (iso) => {
    const changed = iso !== input.value;
    input.value = iso;
    closePopover();
    btn.focus();
    if (changed) emitChange(input);
  };

  const open = () => {
    const todayISO = toISO(new Date());
    let focusDate = fromISO(input.value) || fromISO(allowed(todayISO) ? todayISO : (input.min || input.max)) || new Date();

    const cal = h("div", "ui-cal");
    cal.setAttribute("role", "dialog");
    cal.setAttribute("aria-label", btn.title || "选择日期");
    const head = h("div", "ui-cal-head");
    const prev = h("button", "ui-cal-nav");
    prev.type = "button";
    prev.setAttribute("aria-label", "上个月");
    prev.appendChild(icon("prev"));
    const title = h("span", "ui-cal-title");
    title.setAttribute("aria-live", "polite");
    const next = h("button", "ui-cal-nav");
    next.type = "button";
    next.setAttribute("aria-label", "下个月");
    next.appendChild(icon("next"));
    head.append(prev, title, next);

    const grid = h("div", "ui-cal-grid");
    grid.setAttribute("role", "grid");
    const foot = h("div", "ui-cal-foot");
    const clearBtn = h("button", "ui-cal-link", "清除");
    clearBtn.type = "button";
    const todayBtn = h("button", "ui-cal-link", "今天");
    todayBtn.type = "button";
    todayBtn.disabled = !allowed(todayISO);
    foot.append(clearBtn, todayBtn);
    cal.append(head, grid, foot);

    const render = (focus) => {
      const y = focusDate.getFullYear();
      const m = focusDate.getMonth();
      title.textContent = `${y}年${m + 1}月`;
      grid.textContent = "";
      for (const wd of WEEKDAYS) grid.appendChild(h("span", "ui-cal-wd", wd));
      const first = new Date(y, m, 1);
      const start = addDays(first, -((first.getDay() + 6) % 7));
      const selectedISO = input.value;
      const focusISO = toISO(focusDate);
      for (let i = 0; i < 42; i++) {
        const d = addDays(start, i);
        const iso = toISO(d);
        const day = h("button", "ui-cal-day", String(d.getDate()));
        day.type = "button";
        day.dataset.date = iso;
        day.tabIndex = iso === focusISO ? 0 : -1;
        if (d.getMonth() !== m) day.classList.add("out");
        if (iso === todayISO) day.classList.add("today");
        if (iso === selectedISO) { day.classList.add("selected"); day.setAttribute("aria-pressed", "true"); }
        day.disabled = !allowed(iso);
        day.setAttribute("aria-label", `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`);
        grid.appendChild(day);
      }
      if (focus) grid.querySelector(`[data-date="${focusISO}"]`)?.focus();
    };

    const moveTo = (d) => {
      const iso = toISO(d);
      if (input.min && iso < input.min) d = fromISO(input.min) || d;
      else if (input.max && iso > input.max) d = fromISO(input.max) || d;
      focusDate = d;
      render(true);
    };

    prev.addEventListener("click", () => moveTo(addMonths(focusDate, -1)));
    next.addEventListener("click", () => moveTo(addMonths(focusDate, 1)));
    grid.addEventListener("click", (e) => {
      const day = e.target.closest(".ui-cal-day");
      if (day && !day.disabled) choose(day.dataset.date);
    });
    clearBtn.addEventListener("click", () => choose(""));
    todayBtn.addEventListener("click", () => choose(todayISO));

    cal.addEventListener("keydown", (e) => {
      e.stopPropagation();
      if (e.key === "Escape") {
        e.preventDefault();
        closePopover();
        btn.focus();
        return;
      }
      if (!e.target.classList.contains("ui-cal-day")) return;
      const moves = {
        ArrowLeft: () => addDays(focusDate, -1),
        ArrowRight: () => addDays(focusDate, 1),
        ArrowUp: () => addDays(focusDate, -7),
        ArrowDown: () => addDays(focusDate, 7),
        PageUp: () => addMonths(focusDate, -1),
        PageDown: () => addMonths(focusDate, 1),
        Home: () => addDays(focusDate, -((focusDate.getDay() + 6) % 7)),
        End: () => addDays(focusDate, 6 - ((focusDate.getDay() + 6) % 7)),
      };
      if (moves[e.key]) {
        e.preventDefault();
        moveTo(moves[e.key]());
      }
    });
    grid.addEventListener("focusin", (e) => {
      const d = e.target.dataset && fromISO(e.target.dataset.date);
      if (d) focusDate = d;
    });

    render(false);
    btn.setAttribute("aria-expanded", "true");
    openPopover(btn, cal, () => btn.setAttribute("aria-expanded", "false"));
    const target = grid.querySelector(".ui-cal-day.selected") || grid.querySelector('.ui-cal-day[tabindex="0"]');
    (target && !target.disabled ? target : todayBtn).focus();
  };

  btn.addEventListener("click", () => {
    if (btn.getAttribute("aria-expanded") === "true") closePopover();
    else open();
  });
  btn.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); open(); }
  });
}

/** 把 root 内的原生下拉与日期框换成自绘控件；重复调用安全（已增强的会跳过） */
export function enhance(root = document) {
  const el = resolve(root);
  if (!el) return;
  el.querySelectorAll("select").forEach(enhanceSelect);
  el.querySelectorAll('input[type="date"]').forEach(enhanceDate);
}
