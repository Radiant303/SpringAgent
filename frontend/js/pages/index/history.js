/**
 * 历史消息拉取与回放渲染
 */

import { $, el, scrollToBottom } from "../../utils/dom.js";
import { stringifyContent } from "../../utils/format.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { SproutManager } from "../../components/sprout.js";
import { state, AUTO_SOURCES, resetFlow, createAssistantTurnNode, updateLatestPet, setStickToBottom } from "./state.js";
import {
  userNode,
  noticeNode,
  systemNode,
  answerNode,
  thinkingNode,
  workGroupNode,
  addToGroup,
  refreshWorkSummary,
  historyToolCard,
  addSystem,
} from "./chatView.js";

export function clearMessages(loading = false) {
  const box = $("messages");
  if (box) box.textContent = "";
  if (loading) UI.showSkeleton("messages");
  if (typeof SproutManager !== "undefined" && SproutManager?.clear) {
    SproutManager.clear();
  }
  resetFlow();
  state.tools.clear();
  state.historyCursor = null;
  state.historyHasMore = false;
  const planDock = $("plan-dock");
  if (planDock) planDock.classList.add("hidden");
}

export function renderHistory(segments, prepend = false) {
  const box = $("messages");
  if (!box) return;
  const calls = new Map();
  const results = new Map();
  for (const seg of segments) {
    for (const msg of seg) {
      for (const part of msg.parts || []) {
        if (part.part_kind === "tool-call") calls.set(part.tool_call_id, part);
        else if ((part.part_kind === "tool-return" || part.part_kind === "retry-prompt") && part.tool_call_id) {
          results.set(part.tool_call_id, part);
        }
      }
    }
  }

  const frag = document.createDocumentFragment();
  let currentTurn = null;
  let group = null;

  const turnFor = () => {
    if (!currentTurn) {
      currentTurn = createAssistantTurnNode(false);
      frag.appendChild(currentTurn.el);
    }
    return currentTurn;
  };

  const endGroup = () => {
    if (group) {
      refreshWorkSummary(group);
      group = null;
    }
  };

  const groupFor = () => {
    if (!group) {
      group = workGroupNode();
      turnFor().content.appendChild(group.el);
    }
    return group;
  };

  const endTurn = () => {
    endGroup();
    currentTurn = null;
  };

  const appendAnswer = (node) => {
    endGroup();
    turnFor().content.appendChild(node);
  };

  const appendRoot = (node) => {
    endTurn();
    frag.appendChild(node);
  };

  const rendered = new Set();
  const renderTool = (part) => {
    const id = part.tool_call_id;
    if (!id || rendered.has(id)) return;
    rendered.add(id);
    const call = calls.get(id);
    const result = results.get(id);
    const card = historyToolCard(
      (call && call.tool_name) || part.tool_name || "tool",
      call ? stringifyContent(call.args) : "",
      result ? stringifyContent(result.content) : null,
      result ? result.part_kind === "retry-prompt" : false,
    );
    addToGroup(groupFor(), card);
  };

  segments.forEach((seg, i) => {
    if (i > 0) appendRoot(systemNode("── 上下文已压缩，以上内容已压缩为摘要 ──"));
    for (const msg of seg) {
      const source = msg.metadata && msg.metadata.source;
      if (msg.kind === "request") {
        for (const part of msg.parts || []) {
          if (part.part_kind === "user-prompt" && typeof part.content === "string") {
            if (source === "background_task") appendRoot(noticeNode(part.content));
            else if (!AUTO_SOURCES.has(source)) appendRoot(userNode(part.content));
          } else if (part.part_kind === "tool-return" || part.part_kind === "retry-prompt") {
            renderTool(part);
          }
        }
      } else if (msg.kind === "response") {
        let text = "";
        let thinking = "";
        const flushThinking = () => {
          if (thinking) addToGroup(groupFor(), thinkingNode(thinking));
          thinking = "";
        };
        const flushText = () => {
          if (text) appendAnswer(answerNode(text));
          text = "";
        };
        for (const part of msg.parts || []) {
          if (part.part_kind === "text") {
            flushThinking();
            text += part.content || "";
          } else if (part.part_kind === "thinking") {
            flushText();
            thinking += part.content || "";
          } else if (part.part_kind === "tool-call") {
            flushThinking();
            flushText();
            if (!results.has(part.tool_call_id)) renderTool(part);
          }
        }
        flushThinking();
        flushText();
      }
    }
  });
  endTurn();

  if (prepend) {
    const anchor = document.querySelector(".load-more");
    const oldHeight = box.scrollHeight;
    box.insertBefore(frag, anchor ? anchor.nextSibling : box.firstChild);
    box.scrollTop = box.scrollHeight - oldHeight;
  } else {
    box.appendChild(frag);
    setStickToBottom(true);
    scrollToBottom();
  }

  updateLatestPet();
}

export async function loadHistory(cursor) {
  const params = { sessionId: state.sessionId, limit: 30, direction: "backward" };
  if (cursor) params.cursor = cursor;
  let page;
  try {
    page = await rpc.request("session/history", params);
  } finally {
    if (!cursor) UI.clearSkeleton("messages");
  }
  state.historyCursor = page.previousCursor || null;
  state.historyHasMore = !!page.hasMore;
  renderHistory(page.segments || [], !!cursor);
  updateLoadMore();
}

export function updateLoadMore() {
  const old = document.querySelector(".load-more");
  if (old) old.remove();
  if (!state.historyHasMore) return;
  const btn = el("button", "load-more", "加载更早的消息");
  btn.onclick = async () => {
    btn.disabled = true;
    try {
      await loadHistory(state.historyCursor);
    } catch (e) {
      addSystem("❌ 加载历史失败：" + e.message, true);
    }
  };
  const box = $("messages");
  if (box) box.prepend(btn);
}
