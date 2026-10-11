/**
 * 骨架屏与表格三态加载协调器
 */

import { h, $ } from "../utils/dom.js";

const SK_BLOCK = ":scope > .sk-block";
const SKELETON_WIDTHS = ["70%", "90%", "55%", "80%", "65%", "45%"];
const skeletonTpl = new WeakMap();

const resolve = (x) => (typeof x === "string" ? $(x) : x);

const columnHeads = (tbody) => {
  const table = tbody.closest("table");
  if (table?.tHead?.rows[0]?.cells) return table.tHead.rows[0].cells;
  const ths = table?.querySelectorAll("th");
  if (ths && ths.length) return ths;
  return [];
};

export function saveSkeleton(box) {
  const el = resolve(box);
  const block = el && el.querySelector(SK_BLOCK);
  if (block) skeletonTpl.set(el, block.cloneNode(true));
}

export function showSkeleton(box) {
  const el = resolve(box);
  const tpl = el && skeletonTpl.get(el);
  if (tpl) el.replaceChildren(tpl.cloneNode(true));
}

export function clearSkeleton(box) {
  const el = resolve(box);
  if (el) el.querySelectorAll(SK_BLOCK).forEach((n) => n.remove());
}

export function tableSkeleton(tbody, rows = 10) {
  const el = resolve(tbody);
  if (!el) return;
  const heads = columnHeads(el);
  const n = Math.min(rows || 10, 20);
  const cols = heads.length || 6;
  el.textContent = "";
  for (let r = 0; r < n; r++) {
    const tr = h("tr", "sk-tr");
    tr.setAttribute("aria-hidden", "true");
    for (let c = 0; c < cols; c++) {
      const td = h("td", heads[c]?.className || null);
      const bar = h("span", "sk");
      const w = SKELETON_WIDTHS[(r * cols + c) % SKELETON_WIDTHS.length];
      bar.style.width = w;
      bar.style.height = "16px";
      bar.style.borderRadius = "6px";
      td.appendChild(bar);
      tr.appendChild(td);
    }
    el.appendChild(tr);
  }
}

export function emptyRow(tbody, text = "暂无数据") {
  const el = resolve(tbody);
  if (!el) return;
  const heads = columnHeads(el);
  const td = h("td", null, text);
  td.colSpan = heads.length || 1;
  const tr = h("tr", "empty-row");
  tr.appendChild(td);
  el.replaceChildren(tr);
}

export async function loadTable(tbody, fetcher) {
  const el = resolve(tbody);
  if (!el) return fetcher ? fetcher() : null;
  const card = el.closest(".table-wrap") || el.closest(".table-card");
  const hasRealRows = !!el.querySelector("tr:not(.sk-tr):not(.empty-row)");
  if (hasRealRows && card) {
    card.classList.add("fetching");
    card.classList.add("is-loading");
  } else {
    tableSkeleton(el);
  }
  try {
    return await fetcher();
  } catch (e) {
    if (el.querySelector(".sk-tr")) emptyRow(el, "加载失败");
    throw e;
  } finally {
    if (card) {
      card.classList.remove("fetching");
      card.classList.remove("is-loading");
    }
  }
}

export function renderRows(tbody, items, updateRow, emptyText = "暂无数据") {
  const el = resolve(tbody);
  if (!el) return;

  if (!items || items.length === 0) {
    emptyRow(el, emptyText);
    return;
  }

  const hasSkeleton = !!el.querySelector(".sk-tr");
  const hasEmpty = !!el.querySelector(".empty-row");
  const forceRebuild = hasSkeleton || hasEmpty || el.children.length === 0;

  if (forceRebuild) {
    el.textContent = "";
    items.forEach((item, i) => {
      const tr = document.createElement("tr");
      updateRow(tr, item, i);
      el.appendChild(tr);
    });
    return;
  }

  while (el.children.length > items.length) {
    el.removeChild(el.lastChild);
  }
  while (el.children.length < items.length) {
    const tr = document.createElement("tr");
    el.appendChild(tr);
  }

  Array.from(el.children).forEach((tr, i) => {
    updateRow(tr, items[i], i);
  });
}

export function settleSkeletons(root) {
  const el = resolve(root);
  if (!el) return;
  el.querySelectorAll("tbody").forEach((tb) => {
    if (tb.querySelector(".sk-tr")) emptyRow(tb, "加载失败");
  });
  el.querySelectorAll(".sk-block").forEach((n) => n.replaceWith(h("div", "empty-tip", "加载失败")));
  el.querySelectorAll(".sk").forEach((n) => n.replaceWith("-"));
}
