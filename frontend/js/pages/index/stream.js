/**
 * 实时事件流调度与处理
 */

import { $, el, hidePending, markScrolling, icon } from "../../utils/dom.js";
import { getToken } from "../../api/auth.js";
import { rpc } from "../../api/rpc.js";
import { setMarkdown } from "../../utils/markdown.js";
import { SproutManager } from "../../components/sprout.js";
import {
  state,
  ensureAssistant,
  ensureWork,
  setActive,
  closeWork,
  foldTurnGroups,
  updateLatestPet,
  maybeScroll,
} from "./state.js";
import {
  parseArgs,
  targetFromArgs,
  targetFromPartial,
  setToolTarget,
  setToolStats,
  statsFromArgs,
  fileRowsFromArgs,
  renderFileView,
  colorizeDiff,
  diffStats,
  setToolStatus,
  applyReadMeta,
  parseReadResult,
  refreshWorkSummary,
  buildToolCard,
  thinkingNode,
  addToGroup,
  noticeNode,
  addSystem,
} from "./chatView.js";
import { updateInputState } from "./input.js";
import { refreshSessionList } from "./sessions.js";

export const USAGE_RING_LEN = 56.5;

export async function refreshPoints() {
  const value = $("points-value");
  const token = getToken();
  if (!value || !token) return;
  const settle = () => {
    if (value.querySelector(".sk")) value.textContent = "积分 -";
  };
  try {
    const r = await fetch("/api/billing/me", { headers: { Authorization: "Bearer " + token } });
    if (!r.ok) {
      settle();
      return;
    }
    const data = (await r.json()).data;
    const v = Number(data?.balance ?? 0);
    value.textContent = "积分 " + parseFloat(v.toFixed(6)).toLocaleString(undefined, { maximumFractionDigits: 6 });
    const card = $("points-card");
    if (card) card.classList.toggle("negative", v < 0);
  } catch (e) {
    settle();
  }
}

export function updateUsage(tokens) {
  const model = state.models.find((m) => m.id === state.model);
  const max = model ? model.maxContextSize : 0;
  const prog = $("usage-ring-prog");
  const tip = $("usage-tip");
  if (!prog || !tip) return;
  if (!max) {
    prog.style.strokeDashoffset = String(USAGE_RING_LEN);
    tip.textContent = `已使用 ${(tokens / 1000).toFixed(1)}k tokens`;
    return;
  }
  const pct = Math.min(tokens / max, 1);
  prog.style.strokeDashoffset = String(USAGE_RING_LEN * (1 - pct));
  tip.textContent = `使用 ${(tokens / 1000).toFixed(1)}k / ${Math.round(max / 1000)}k tokens (${Math.round(pct * 100)}%)`;
}

export async function loadPlan() {
  const sid = state.sessionId;
  try {
    const r = await rpc.request("session/plan", { sessionId: sid });
    if (sid === state.sessionId) renderPlan(r.items);
  } catch {
    /* 旧版服务端没有该方法：退化为只靠 plan_updated 事件 */
  }
}

export function renderPlan(items) {
  const dock = $("plan-dock");
  const panel = $("plan-panel");
  const list = $("plan-items");
  if (!dock || !panel || !list) return;
  if (!items || !items.length) {
    dock.classList.add("hidden");
    return;
  }
  dock.classList.remove("hidden");
  list.textContent = "";
  let done = 0;
  for (const item of items) {
    const status = item.status || "pending";
    if (status === "completed") done++;
    const li = el("li", status);
    li.appendChild(status === "completed" ? icon("planCheck", "plan-mark") : el("span", "plan-mark"));
    const text = status === "in_progress" && item.active_form ? item.active_form : item.content || "";
    li.appendChild(el("span", "plan-text", text));
    list.appendChild(li);
  }
  const label = `${done}/${items.length}`;
  if ($("plan-progress")) $("plan-progress").textContent = label;
  if ($("plan-pill-count")) $("plan-pill-count").textContent = label;
  if ($("plan-pill")) $("plan-pill").setAttribute("aria-label", `当前进度 ${label}`);
  panel.classList.toggle("all-done", done === items.length);
}

export function seqAfter(a, b) {
  const [am, as] = a.split("-").map(Number);
  const [bm, bs] = b.split("-").map(Number);
  return am > bm || (am === bm && as > bs);
}

export function onSessionEvent(sid, event, seq) {
  if (sid !== state.sessionId) return;
  if (seq) {
    const last = state.lastSeq[sid];
    if (last && !seqAfter(seq, last)) return;
    state.lastSeq[sid] = seq;
  }
  hidePending();
  switch (event.kind) {
    case "text_delta": {
      const a = ensureAssistant();
      if (!a.text) setActive(null);
      a.text += event.text;
      setMarkdown(a.answer, a.text);
      maybeScroll();
      break;
    }
    case "thinking_delta": {
      const group = ensureWork();
      if (!state.thinking) {
        state.thinking = thinkingNode("");
        addToGroup(group, state.thinking);
        setActive(state.thinking);
      }
      const body = state.thinking.querySelector(".thinking-body");
      const atBottom = body.scrollHeight - body.scrollTop - body.clientHeight < 40;
      body.textContent += event.text;
      if (atBottom) {
        body.scrollTop = body.scrollHeight;
        markScrolling(body);
      }
      maybeScroll();
      break;
    }
    case "tool_started": {
      const group = ensureWork();
      state.thinking = null;
      const card = buildToolCard(event.tool_name, event.tool_call_id);
      state.tools.set(event.tool_call_id, card);
      addToGroup(group, card);
      setActive(card.el);
      maybeScroll();
      break;
    }
    case "tool_args_delta": {
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.args.textContent += event.args_chunk;
        const args = parseArgs(card.args.textContent);
        const target = args ? targetFromArgs(args) : targetFromPartial(card.args.textContent);
        if (target && target !== card.target) {
          setToolTarget(card, target);
          if (state.work) refreshWorkSummary(state.work);
        }
        if (args && !card.hasDiff) {
          setToolStats(card, statsFromArgs(card.name, args));
          const rows = fileRowsFromArgs(card.name, args);
          if (rows) renderFileView(card, rows);
        }
        maybeScroll();
      }
      break;
    }
    case "tool_diff": {
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.hasDiff = true;
        card.diff.classList.remove("hidden");
        colorizeDiff(card.diff, event.diff);
        setToolStats(card, diffStats(event.diff));
        const rows = parseUnifiedDiff(event.diff);
        if (rows.length) renderFileView(card, rows);
      }
      break;
    }
    case "tool_pending": {
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.pending.textContent = event.label || "等待批准";
        card.pending.classList.remove("hidden");
      }
      break;
    }
    case "tool_finished": {
      const card = state.tools.get(event.tool_call_id);
      if (card) {
        card.pending.classList.add("hidden");
        setToolStatus(card, event.is_error ? "error" : "done");
        card.result.textContent = event.result;
        card.result.classList.remove("hidden");
        card.result.classList.toggle("error", !!event.is_error);
        if (card.name === "read_file" && !event.is_error) {
          applyReadMeta(card, event.result);
          const read = parseReadResult(event.result);
          if (read.rows.length || read.note) renderFileView(card, read.rows, read.note);
        }
        if (card.group) refreshWorkSummary(card.group);
        maybeScroll();
      }
      break;
    }
    case "usage": {
      updateUsage(event.context_tokens);
      break;
    }
    case "plan_updated": {
      renderPlan(event.items);
      break;
    }
    case "teaching_updated": {
      const unit = event.unit || {};
      addSystem(`📘 教学单元更新：${unit.title || unit.slug || ""}（${unit.status || ""}）`);
      break;
    }
    case "compaction": {
      addSystem(`上下文已压缩：折叠 ${event.dropped} 条旧消息（约 ${Math.round(event.before / 1000)}k → ${Math.round(event.after / 1000)}k tokens）`);
      break;
    }
    case "background_notification": {
      closeWork();
      state.assistant = null;
      const msgBox = $("messages");
      if (msgBox) msgBox.appendChild(noticeNode(event.text));
      maybeScroll();
      break;
    }
    case "background_task_started": {
      addSystem(`⚙ 后台任务 ${event.task_id} 已开始（${event.tool_name}），完成后会自动汇报`);
      break;
    }
    case "background_task_finished": {
      if (event.cancelled) addSystem(`⚙ 后台任务 ${event.task_id}（${event.tool_name}）已取消`);
      else if (event.is_error) addSystem(`⚙ 后台任务 ${event.task_id}（${event.tool_name}）出错，详情见后续汇报`, true);
      break;
    }
    case "turn_finished": {
      finishTurn(event);
      break;
    }
  }
}

export function finishTurn(event) {
  const groups = new Set();
  for (const card of state.tools.values()) {
    card.pending.classList.add("hidden");
    if (card.status.classList.contains("running")) {
      setToolStatus(card, "done");
    }
    if (card.group) groups.add(card.group);
  }
  groups.forEach(refreshWorkSummary);
  setActive(null);
  closeWork();
  foldTurnGroups();
  state.assistant = null;
  state.tools.clear();
  if (state.currentTurn && state.currentTurn.content.children.length === 0) {
    if (typeof SproutManager !== "undefined" && state.currentTurn.pet && SproutManager?.unregister) {
      SproutManager.unregister(state.currentTurn.pet);
    }
    state.currentTurn.el.remove();
  }
  state.currentTurn = null;
  updateLatestPet();
  if (event.cancelled && !event.wake) addSystem("已中断，可继续输入");
  else if (event.error) addSystem("❌ 运行出错：" + event.error, true);
  if (!event.wake) {
    state.busy = false;
    updateInputState();
  }
  setTimeout(refreshPoints, 1500);
  refreshSessionList();
}
