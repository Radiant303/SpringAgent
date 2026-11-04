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

function paintLoadingBar(bar, value) {
  const fill = $("admin-loading-fill");
  if (fill) fill.style.width = `${value}%`;
  bar.style.setProperty("--progress", `${value}%`);
  bar.setAttribute("aria-valuenow", String(Math.round(value)));
}

export function nextLoadingProgress(value, random = Math.random()) {
  const min = Math.min(10, (100 - value) / 5);
  const max = Math.min(20, (100 - value) / 3);
  const next = value + min + (max - min) * random;
  return next < MAX_PROGRESS ? next : value;
}

export function startLoadingBar() {
  const current = ++generation;
  clearTimers();
  const bar = $("admin-loading-bar");
  if (!bar) return current;
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  progress = reducedMotion ? 80 : 10 + Math.floor(Math.random() * 11);
  bar.classList.remove("is-active", "is-complete");
  paintLoadingBar(bar, 0);
  bar.setAttribute("aria-hidden", "false");
  void bar.offsetWidth;
  bar.classList.add("is-active");
  paintLoadingBar(bar, progress);
  if (!reducedMotion) {
    progressTimer = setInterval(() => {
      if (current !== generation) return;
      progress = nextLoadingProgress(progress);
      paintLoadingBar(bar, progress);
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
  paintLoadingBar(bar, 100);
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
