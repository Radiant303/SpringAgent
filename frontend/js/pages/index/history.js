/**
 * 历史消息拉取与回放渲染
 */

import { $, el, scrollToBottom } from "../../utils/dom.js";
import { stringifyContent } from "../../utils/format.js";
import { api } from "../../api/client.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { SproutManager } from "../../components/sprout.js";
import { state, AUTO_SOURCES, resetFlow, createAssistantTurnNode, updateLatestPet, setStickToBottom, isBusy } from "./state.js";
import { branchInfo, branchToolbar } from "./branches.js";
import {
  userNode,
  systemNode,
  answerNode,
  thinkingNode,
  workGroupNode,
  addToGroup,
  refreshWorkSummary,
  historyToolCard,
  addSystem,
  foldTurnProcess,
} from "./chatView.js";

export function clearMessages(loading = false, preservePlan = false) {
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
  if (planDock && !preservePlan) planDock.classList.add("hidden");
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
  let turnText = "";
  let turnBranch = null;

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
    if (currentTurn) {
      foldTurnProcess(currentTurn.content);
      const tools = branchToolbar(turnBranch, {
        kind: "assistant",
        text: turnText,
        retryLimit: state.answerRetryLimit,
        busy: isBusy(state.sessionId),
      });
      if (tools) currentTurn.content.appendChild(tools);
    }
    currentTurn = null;
    turnText = "";
    turnBranch = null;
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
            if (!AUTO_SOURCES.has(source)) appendRoot(userNode(part.content, branchInfo(msg)));
          } else if (part.part_kind === "tool-return" || part.part_kind === "retry-prompt") {
            renderTool(part);
          }
        }
      } else if (msg.kind === "response") {
        turnBranch = branchInfo(msg) || turnBranch;
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
            turnText += part.content || "";
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

function latestLeaf(nodes, branchId) {
  const children = new Map();
  for (const node of nodes) {
    if (!node.parentId) continue;
    if (!children.has(node.parentId)) children.set(node.parentId, []);
    children.get(node.parentId).push(node);
  }
  for (const items of children.values()) {
    items.sort((a, b) => String(a.createdAt || "").localeCompare(String(b.createdAt || "")) || a.branchId.localeCompare(b.branchId));
  }
  let current = branchId;
  const visited = new Set([current]);
  while (children.get(current)?.length) {
    const next = children.get(current).at(-1).branchId;
    if (visited.has(next)) break;
    current = next;
    visited.add(current);
  }
  return current;
}

export function renderNodePath(branchId = null) {
  const nodes = state.messageNodes;
  if (!Array.isArray(nodes)) return false;
  if (!nodes.length) {
    clearMessages();
    return true;
  }
  const byId = new Map(nodes.map((node) => [node.branchId, node]));
  const selected = branchId || state.branchSelections.get(state.sessionId) || state.activeNodeId || nodes.at(-1).branchId;
  const leaf = latestLeaf(nodes, byId.has(selected) ? selected : nodes.at(-1).branchId);
  const path = [];
  const visited = new Set();
  let current = leaf;
  while (current && byId.has(current) && !visited.has(current)) {
    const node = byId.get(current);
    path.push(node);
    visited.add(current);
    current = node.parentId;
  }
  path.reverse();
  const contents = [];
  for (const node of path) {
    for (const message of node.messages || []) {
      contents.push({
        ...message,
        metadata: { ...(message.metadata || {}), springAgent: { ...node, messages: undefined } },
      });
    }
  }
  state.activeNodeId = leaf;
  state.branchSelections.set(state.sessionId, leaf);
  clearMessages(false, true);
  renderHistory([contents]);
  return true;
}

function applyMessageNodes(page, sessionId) {
  if (!Array.isArray(page.nodes)) return null;
  state.messageNodes = page.nodes;
  state.activeNodeId = page.activeNodeId || page.nodes.at(-1)?.branchId || null;
  state.branchSelections.set(sessionId, state.activeNodeId);
  return page.nodes.find((node) => node.branchId === state.activeNodeId) || null;
}

export async function refreshMessageTree(generation) {
  const sessionId = state.sessionId;
  const workId = state.workId;
  const socket = rpc.ws;
  const page = await api(`/api/sessions/${encodeURIComponent(sessionId)}/history`);
  if (sessionId !== state.sessionId || workId !== state.workId || socket !== rpc.ws
      || !state.connected || generation !== state.viewGeneration) return null;
  return applyMessageNodes(page, sessionId);
}

export async function loadHistory(cursor, generation, signal) {
  const sid = state.sessionId;
  const workId = state.workId;
  const socket = rpc.ws;
  const isCurrent = () => state.sessionId === sid && state.workId === workId && rpc.ws === socket && state.connected
    && (generation === undefined || generation === state.viewGeneration);
  let page;
  try {
    page = await api(`/api/sessions/${encodeURIComponent(sid)}/history`, { signal });
  } finally {
    if (!cursor && isCurrent()) UI.clearSkeleton("messages");
  }
  if (!isCurrent()) return;
  state.historyCursor = null;
  state.historyHasMore = false;
  if (Array.isArray(page.nodes)) {
    applyMessageNodes(page, sid);
    renderNodePath(state.activeNodeId);
  } else {
    renderHistory(page.segments || [], false);
  }
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
