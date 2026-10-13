/**
 * 服务器反向请求弹窗：操作审批与用户提问
 */

import { $, el } from "../../utils/dom.js";
import { colorizeDiff } from "./chatView.js";

let approvalResolve = null;
let questionResolve = null;
let approvalQueue = Promise.resolve();
let questionQueue = Promise.resolve();
let approvalGeneration = 0;
let questionGeneration = 0;

export function handleApprovalRequest(params) {
  const generation = approvalGeneration;
  const task = approvalQueue.then(() => new Promise((resolve) => {
    if (generation !== approvalGeneration) { resolve(false); return; }
    approvalResolve = resolve;
    if ($("approval-tool")) $("approval-tool").textContent = params.toolName || "";
    if ($("approval-args")) $("approval-args").textContent = JSON.stringify(params.args ?? {}, null, 2);
    const diffEl = $("approval-diff");
    if (diffEl) {
      if (params.diff) {
        diffEl.classList.remove("hidden");
        colorizeDiff(diffEl, params.diff);
      } else {
        diffEl.classList.add("hidden");
        diffEl.textContent = "";
      }
    }
    const modal = $("approval-modal");
    if (modal) modal.classList.remove("hidden");
  }));
  approvalQueue = task.catch(() => {});
  return task.then((approved) => ({ approved }));
}

export function settleApproval(approved) {
  const modal = $("approval-modal");
  if (modal) modal.classList.add("hidden");
  const resolve = approvalResolve;
  approvalResolve = null;
  if (resolve) resolve(approved);
}

export function hideApproval() {
  approvalGeneration++;
  settleApproval(false);
}

export function handleQuestionRequest(params) {
  const generation = questionGeneration;
  const task = questionQueue.then(() => new Promise((resolve) => {
    if (generation !== questionGeneration) { resolve(null); return; }
    questionResolve = resolve;
    if ($("question-text")) $("question-text").textContent = params.question || "";
    const optionsBox = $("question-options");
    if (optionsBox) {
      optionsBox.textContent = "";
      (params.options || []).forEach((opt, i) => {
        const btn = el("button", "q-option");
        btn.appendChild(el("span", "q-opt-label", String(opt)));
        btn.appendChild(el("span", "q-num", String(i + 1)));
        btn.onclick = () => settleQuestion(String(opt));
        optionsBox.appendChild(btn);
      });
    }
    if ($("question-custom-row")) $("question-custom-row").classList.toggle("hidden", !params.allowCustom);
    if ($("question-actions")) $("question-actions").classList.toggle("hidden", !params.allowCustom);
    if ($("question-custom-num")) $("question-custom-num").textContent = String((params.options || []).length + 1);
    if ($("question-custom")) $("question-custom").value = "";
    if ($("input-card")) $("input-card").classList.add("hidden");
    if ($("question-card")) $("question-card").classList.remove("hidden");
    if (params.allowCustom && $("question-custom")) $("question-custom").focus();
  }));
  questionQueue = task.catch(() => {});
  return task.then((answer) => ({ answer }));
}

export function settleQuestion(answer) {
  if ($("question-card")) $("question-card").classList.add("hidden");
  if ($("input-card")) $("input-card").classList.remove("hidden");
  const resolve = questionResolve;
  questionResolve = null;
  if (resolve) resolve(answer);
}

export function hideQuestion() {
  questionGeneration++;
  settleQuestion(null);
}
