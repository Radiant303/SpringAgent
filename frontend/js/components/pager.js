/**
 * 表格分页条：总数 + 每页条数 + 首页/上一页/页码/下一页/末页
 */

import { h, $, icon } from "../utils/dom.js";
import { fmtNum } from "../utils/format.js";
import { enhanceSelect } from "./controls.js";

/** 每页条数选项。 */
const SIZE_OPTIONS = [10, 20, 50, 100];

/** 页码序列：pages<=7 全列，否则 首尾 + 当前页±1 + 省略号。 */
function pageItems(page, pages) {
  if (pages <= 7) {
    return Array.from({ length: pages }, (_, i) => i + 1);
  }
  const items = [1];
  const lo = Math.max(2, page - 1);
  const hi = Math.min(pages - 1, page + 1);
  if (lo > 2) items.push("…");
  for (let i = lo; i <= hi; i++) items.push(i);
  if (hi < pages - 1) items.push("…");
  items.push(pages);
  return items;
}

/**
 * 渲染分页条。
 * @param {string|HTMLElement} el 容器（.table-pager）
 * @param {{page:number, size:number, total:number}} p 分页数据
 * @param {{onPage:(page:number)=>void, onSize:(size:number)=>void}} cb 翻页与改每页条数回调
 */
export function renderPager(el, p, cb) {
  const box = $(el);
  if (!box) return;
  const page = Number(p.page) || 1;
  const size = Number(p.size) || 20;
  const total = Number(p.total) || 0;
  const pages = Math.max(1, Math.ceil(total / size));

  const info = h("span", "pager-info", `共 ${fmtNum(total)} 条`);
  const sel = h("select", "pager-size");
  sel.title = "每页条数";
  for (const n of SIZE_OPTIONS) {
    const opt = h("option", null, String(n));
    opt.value = String(n);
    opt.selected = n === size;
    sel.appendChild(opt);
  }
  sel.addEventListener("change", () => cb.onSize(Number(sel.value)));
  const left = h("span", "pager-left");
  left.append(info, sel);

  const nav = (iconName, title, target, disabled) => {
    const b = h("button", "pager-nav");
    b.type = "button";
    b.title = title;
    b.setAttribute("aria-label", title);
    b.disabled = disabled;
    b.appendChild(icon(iconName));
    b.onclick = () => cb.onPage(target);
    return b;
  };
  const right = h("span", "pager-btns");
  right.append(
    nav("first", "首页", 1, page <= 1),
    nav("prev", "上一页", page - 1, page <= 1),
  );
  for (const it of pageItems(page, pages)) {
    if (it === "…") {
      right.appendChild(h("span", "pager-ellipsis", "…"));
    } else {
      const b = h("button", "pager-num" + (it === page ? " cur" : ""), String(it));
      b.type = "button";
      b.disabled = it === page;
      b.onclick = () => cb.onPage(it);
      right.appendChild(b);
    }
  }
  right.append(
    nav("next", "下一页", page + 1, page >= pages),
    nav("last", "末页", pages, page >= pages),
  );

  box.replaceChildren(left, right);
  enhanceSelect(sel);
}
