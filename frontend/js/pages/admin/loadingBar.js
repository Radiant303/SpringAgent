/**
 * 后台页面顶部加载进度条
 */

import { $ } from "../../utils/dom.js";

let generation = 0;
let timer = null;

export function startLoadingBar() {
  const current = ++generation;
  clearTimeout(timer);
  const bar = $("admin-loading-bar");
  if (!bar) return current;
  bar.classList.remove("is-active", "is-complete");
  bar.setAttribute("aria-hidden", "false");
  void bar.offsetWidth;
  bar.classList.add("is-active");
  return current;
}

export function finishLoadingBar(current) {
  if (current !== generation) return;
  clearTimeout(timer);
  const bar = $("admin-loading-bar");
  if (!bar) return;
  bar.classList.remove("is-active");
  bar.classList.add("is-complete");
  timer = setTimeout(() => {
    if (current !== generation) return;
    bar.classList.remove("is-complete");
    bar.setAttribute("aria-hidden", "true");
  }, 450);
}
