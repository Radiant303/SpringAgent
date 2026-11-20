/**
 * 按会话排队的操作审批与用户提问。
 *
 * @author hanbing
 * @since 2026-10-17
 */

import { $, el } from "../../utils/dom.js";
import { UI } from "../../components/ui.js";
import { colorizeDiff, targetFromArgs, toolMeta } from "./chatView.js";
import { state, runs, runOf, setBusy } from "./state.js";
import { refreshSessionList, sessionLabel } from "./sessions.js";

const pendingBySession = new Map();
const INPUT_CARD_IDS = ["input-card", "question-card", "approval-card"];
/** 输入卡片高度过渡时长（毫秒） */
const INPUT_CARD_SWAP_DURATION = 180;
let inputStageHeightAnimation = null;
let activeRequest = null;
let approvalGeneration = 0;
let questionGeneration = 0;

function generationOf(type) {
  return type === "approval" ? approvalGeneration : questionGeneration;
}

function updateAttention(sid) {
  const run = runs[sid];
  if (!run) return;
  const request = activeRequest?.params.sessionId === sid ? activeRequest : pendingBySession.get(sid)?.[0];
  run.attention = request?.type || null;
}

function swapInputCard(id) {
  const next = $(id);
  if (!next || !next.classList.contains("hidden")) return;
  const stage = $("input-stage");
  const startHeight = stage?.getBoundingClientRect().height || 0;
  inputStageHeightAnimation?.cancel();
  for (const cardId of INPUT_CARD_IDS) {
    const card = $(cardId);
    card?.classList.toggle("hidden", cardId !== id);
    if (card) card.inert = cardId !== id;
  }
  if (!stage || window.matchMedia("(prefers-reduced-motion: reduce)").matches
      || typeof stage.animate !== "function") return;
  const endHeight = next.getBoundingClientRect().height;
  if (!startHeight || startHeight === endHeight) return;
  inputStageHeightAnimation = stage.animate([
    { height: `${startHeight}px`, overflow: "hidden" },
    { height: `${endHeight}px`, overflow: "hidden" },
  ], { duration: INPUT_CARD_SWAP_DURATION, easing: "cubic-bezier(0.2, 0.8, 0.2, 1)" });
}

function hideCards() {
  swapInputCard("input-card");
}

function enqueueRequest(type, params) {
  const sid = params.sessionId || state.sessionId;
  if (!sid) return Promise.resolve(type === "approval" ? { approved: false } : { answer: null });
  return new Promise((resolve, reject) => {
    const request = { type, params: { ...params, sessionId: sid }, resolve, reject, generation: generationOf(type) };
    const queue = pendingBySession.get(sid) || [];
    queue.push(request);
    pendingBySession.set(sid, queue);
    setBusy(sid, true);
    updateAttention(sid);
    refreshSessionList();
    if (sid === state.sessionId && state.sessionReady) drainPendingFor(sid);
    else UI.toast(type === "approval" ? "会话需要审批" : "会话等待回答", {
      type: "warning",
      description: sessionLabel(sid),
    });
  });
}

export function handleApprovalRequest(params) {
  return enqueueRequest("approval", params);
}

export function handleQuestionRequest(params) {
  return enqueueRequest("question", params);
}

export function drainPendingFor(sid) {
  if (activeRequest && (activeRequest.params.sessionId !== sid || !state.sessionReady)) {
    const request = activeRequest;
    if (request.type === "question") request.custom = $("question-custom")?.value || "";
    const previousSid = request.params.sessionId;
    const queue = pendingBySession.get(previousSid) || [];
    queue.unshift(request);
    pendingBySession.set(previousSid, queue);
    activeRequest = null;
    hideCards();
    updateAttention(previousSid);
  }
  if (!sid || sid !== state.sessionId || !state.sessionReady || !state.connected || activeRequest) return;
  const queue = pendingBySession.get(sid);
  const request = queue?.shift();
  if (!queue?.length) pendingBySession.delete(sid);
  if (!request) {
    if (runs[sid]) runs[sid].attention = null;
    return;
  }
  if (request.generation !== generationOf(request.type)) {
    request.resolve(request.type === "approval" ? { approved: false } : { answer: null });
    drainPendingFor(sid);
    return;
  }
  activeRequest = request;
  runOf(sid).attention = request.type;
  try {
    if (request.type === "approval") showApproval(request.params);
    else showQuestion(request.params, request.custom);
  } catch (error) {
    activeRequest = null;
    hideCards();
    request.reject(error);
    updateAttention(sid);
    drainPendingFor(sid);
  }
  refreshSessionList();
}

function showApproval(params) {
  const target = targetFromArgs(params.args, params.toolName);
  const label = [toolMeta(params.toolName).verb, target].filter(Boolean).join(" · ");
  if ($("approval-tool")) {
    $("approval-tool").textContent = label;
    $("approval-tool").title = label;
  }
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
  swapInputCard("approval-card");
  $("approval-card")?.focus({ preventScroll: true });
}

function showQuestion(params, custom = "") {
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
  $("question-custom-row")?.classList.toggle("hidden", !params.allowCustom);
  $("question-actions")?.classList.toggle("hidden", !params.allowCustom);
  if ($("question-custom-num")) $("question-custom-num").textContent = String((params.options || []).length + 1);
  if ($("question-custom")) $("question-custom").value = custom;
  swapInputCard("question-card");
  if (params.allowCustom) $("question-custom")?.focus();
}

function settleRequest(type, value) {
  if (activeRequest?.type !== type) return;
  const request = activeRequest;
  const sid = request.params.sessionId;
  activeRequest = null;
  hideCards();
  request.resolve(type === "approval" ? { approved: value } : { answer: value });
  updateAttention(sid);
  drainPendingFor(state.sessionId);
  refreshSessionList();
}

export function settleApproval(approved) {
  settleRequest("approval", approved);
}

export function settleQuestion(answer) {
  settleRequest("question", answer);
}

function rejectRequests(predicate) {
  const affected = new Set();
  if (activeRequest && predicate(activeRequest)) {
    const request = activeRequest;
    activeRequest = null;
    hideCards();
    affected.add(request.params.sessionId);
    request.resolve(request.type === "approval" ? { approved: false } : { answer: null });
  }
  for (const [sid, queue] of pendingBySession) {
    const remaining = [];
    for (const request of queue) {
      if (predicate(request)) {
        affected.add(sid);
        request.resolve(request.type === "approval" ? { approved: false } : { answer: null });
      } else remaining.push(request);
    }
    if (remaining.length) pendingBySession.set(sid, remaining);
    else pendingBySession.delete(sid);
  }
  for (const sid of affected) updateAttention(sid);
}

export function clearPendingFor(sid) {
  rejectRequests((request) => request.params.sessionId === sid);
}

export function hideApproval() {
  approvalGeneration++;
  rejectRequests((request) => request.type === "approval");
}

export function hideQuestion() {
  questionGeneration++;
  rejectRequests((request) => request.type === "question");
}
