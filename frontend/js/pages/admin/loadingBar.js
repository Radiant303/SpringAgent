/**
 * 后台页面顶部加载进度条
 */

import { $ } from "../../utils/dom.js";

const STEP_INTERVAL = 1000;
const FINISH_DELAY = 1300;
const MAX_PROGRESS = 95;

let generation = 0;
let progressTimer = null;
let finishTimer = null;
let progress = 0;

function clearTimers() {
  clearInterval(progressTimer);
  clearTimeout(finishTimer);
  progressTimer = null;
  finishTimer = null;
}

export function startLoadingBar() {
  const current = ++generation;
  clearTimers();
  const bar = $("admin-loading-bar");
  if (!bar) return current;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  progress = reducedMotion ? 80 : 10 + Math.random() * 10;
  bar.classList.remove("is-active", "is-complete");
  bar.style.width = "0%";
  bar.setAttribute("aria-hidden", "false");
  void bar.offsetWidth;
  bar.classList.add("is-active");
  bar.style.width = `${progress}%`;
  if (!reducedMotion) {
    progressTimer = setInterval(() => {
      if (current !== generation) return;
      progress = Math.min(MAX_PROGRESS, progress + Math.random() * 10);
      bar.style.width = `${progress}%`;
    }, STEP_INTERVAL);
  }
  return current;
}

export function finishLoadingBar(current) {
  if (current !== generation) return;
  clearTimers();
  const bar = $("admin-loading-bar");
  if (!bar) return;
  bar.classList.remove("is-active");
  bar.classList.add("is-complete");
  bar.style.width = "100%";
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    bar.classList.remove("is-complete");
    bar.setAttribute("aria-hidden", "true");
    return;
  }
  finishTimer = setTimeout(() => {
    if (current !== generation) return;
    bar.classList.remove("is-complete");
    bar.setAttribute("aria-hidden", "true");
  }, FINISH_DELAY);
}
