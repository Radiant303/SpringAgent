/**
 * 聊天页面主入口与事件绑定
 */

import { $, icon } from "../../utils/dom.js";
import { getToken, clearToken, getRole, goLogin, WS_BASE, USER_KEY, ROLE_KEY, ROLE_LABELS } from "../../api/auth.js";
import { rpc } from "../../api/rpc.js";
import { UI } from "../../components/ui.js";
import { state, resetFlow, setStickToBottom, smoothScrollToBottom, updateStick } from "./state.js";
import { addSystem } from "./chatView.js";
import { loadWorks, createWork } from "./works.js";
import { openSession, newSession } from "./sessions.js";
import { onSessionEvent, refreshPoints } from "./stream.js";
import {
  handleApprovalRequest,
  settleApproval,
  hideApproval,
  handleQuestionRequest,
  settleQuestion,
  hideQuestion,
} from "./modals.js";
import {
  updateInputState,
  send,
  cancelTurn,
  populateModelSelect,
  openModelMenu,
  openEffortMenu,
  closeEffortMenu,
  closeModelMenu,
  positionModelMenus,
  modelMenuKeydown,
  refreshModelList,
  syncEmpty,
  syncChrome,
} from "./input.js";

// Markdown 配置
if (typeof marked !== "undefined" && marked.setOptions) {
  marked.setOptions({ breaks: true, gfm: true });
}

// 记下首屏骨架
UI.saveSkeleton("messages");
UI.saveSkeleton("session-list");

let reconnectTimer = null;
let reconnectDelay = 1000;

export function setConnected(ok) {
  state.connected = ok;
  const dot = $("conn-status");
  if (dot) {
    dot.className = "conn-dot " + (ok ? "connected" : "disconnected");
    dot.title = ok ? "已连接" : "未连接";
  }
  const text = $("conn-text");
  if (text) text.textContent = ok ? "已连接" : "未连接";
  updateInputState();
}

export async function connectAndSetup() {
  state.sessionReady = false;
  await rpc.connect(`${WS_BASE}/ws?token=${encodeURIComponent(getToken())}`);
  const info = await rpc.request("initialize", {});
  state.workspace = info.workspace;
  state.models = info.models || [];
  if (!state.models.some((m) => m.id === state.model)) {
    state.model = info.defaultModel || null;
    state.effort = null;
  }
  const model = state.models.find((m) => m.id === state.model);
  state.effort = model?.thinkingEnabled
    ? (model.supportEfforts.includes(state.effort) ? state.effort : model.defaultEffort)
    : null;
  populateModelSelect();
  if ($("input-ws")) $("input-ws").title = state.workspace;
  setConnected(true);
  await loadWorks();
  await openSession();
  await loadWorks();
  reconnectDelay = 1000;
}

export function scheduleReconnect() {
  if (state.connectionReplaced || reconnectTimer) return;
  reconnectTimer = setTimeout(async () => {
    reconnectTimer = null;
    try {
      await connectAndSetup();
    } catch {
      if (!getToken()) return;
      reconnectDelay = Math.min(reconnectDelay * 2, 15000);
      scheduleReconnect();
    }
  }, reconnectDelay);
}

// 绑定 RPC 消息派发
rpc.onServerRequest = (method, params) => {
  if (method === "approval/request") return handleApprovalRequest(params);
  if (method === "question/request") return handleQuestionRequest(params);
  throw new Error("未知请求: " + method);
};

function markReplaced() {
  if (state.connectionReplaced) return;
  state.connectionReplaced = true;
  state.sessionReady = false;
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  setConnected(false);
  hideApproval();
  hideQuestion();
  $("reconnect-btn")?.classList.remove("hidden");
  addSystem("会话已被其他页面或设备接管，本页面已断开。可点击重新连接后主动接管。");
}

rpc.onNotification = (method, params) => {
  if (method === "session/replaced") markReplaced();
  if (method === "session/event" && !state.connectionReplaced) onSessionEvent(params.sessionId, params.event, params.seq);
};

rpc.onClose = (code) => {
  if (code === 4409) markReplaced();
  state.sessionReady = false;
  setConnected(false);
  state.busy = false;
  resetFlow();
  state.tools.clear();
  hideApproval();
  hideQuestion();
  if (code === 4401) {
    goLogin("expired");
    return;
  }
  if (code === 4403) {
    goLogin("disabled");
    return;
  }
  if (state.connectionReplaced) return;
  if (!reconnectTimer) {
    addSystem("连接已断开，正在重连…");
    scheduleReconnect();
  }
};

/* ================= 用户菜单与状态栏 ================= */

export function showUserChip() {
  const name = localStorage.getItem(USER_KEY);
  if (name) {
    const el = $("user-name");
    if (el) {
      el.textContent = name;
      el.title = name;
    }
    const role = getRole();
    if ($("user-badge")) $("user-badge").textContent = ROLE_LABELS[role] || "用户";
    if ($("um-admin")) $("um-admin").classList.toggle("hidden", role !== "owner" && role !== "admin");
    if ($("um-settings")) $("um-settings").classList.toggle("hidden", role !== "owner");
    if ($("user-chip")) $("user-chip").classList.remove("hidden");
    if ($("points-card")) $("points-card").classList.remove("hidden");
    loadCommunityLink();
    refreshPoints();
  }
}

/* 官方社群入口 */
function loadCommunityLink() {
  const item = $("um-community");
  if (!item) return;
  fetch("/api/auth/public-config")
    .then((r) => r.json())
    .then((p) => {
      const link = p && p.data && typeof p.data.communityLink === "string"
        ? p.data.communityLink.trim()
        : "";
      if (link) {
        item.href = link;
        item.classList.remove("hidden");
      } else {
        item.classList.add("hidden");
      }
    })
    .catch(() => {});
}

export function toggleUserMenu(open) {
  const menu = $("user-menu");
  if (!menu) return;
  const next = open ?? menu.classList.contains("hidden");
  menu.classList.toggle("hidden", !next);
  if ($("user-chip")) $("user-chip").classList.toggle("open", next);
  if (next) refreshPoints();
}

export function bindAccountMenu() {
  if ($("logout-btn")) {
    $("logout-btn").onclick = () => {
      clearToken();
      localStorage.removeItem(USER_KEY);
      localStorage.removeItem(ROLE_KEY);
      localStorage.removeItem("sh.sessionId");
      goLogin();
    };
  }
  if ($("user-chip")) {
    $("user-chip").onclick = (e) => {
      e.stopPropagation();
      toggleUserMenu();
    };
  }
  document.addEventListener("click", (e) => {
    if (!e.target.closest("#user-menu") && !e.target.closest("#user-chip")) {
      toggleUserMenu(false);
    }
  });
}

/* ================= 事件绑定 ================= */

export function bind() {
  if ($("reconnect-btn")) $("reconnect-btn").onclick = async () => {
    const button = $("reconnect-btn");
    if (button.disabled) return;
    button.disabled = true;
    state.connectionReplaced = false;
    try {
      await connectAndSetup();
      if (!state.connectionReplaced) button.classList.add("hidden");
    } catch (e) {
      addSystem("重新连接失败：" + e.message, true);
      scheduleReconnect();
    } finally {
      button.disabled = false;
    }
  };
  if ($("send-btn")) $("send-btn").onclick = send;
  if ($("stop-btn")) $("stop-btn").onclick = cancelTurn;
  if ($("new-session-btn")) $("new-session-btn").onclick = newSession;
  if ($("new-work-btn")) $("new-work-btn").onclick = createWork;

  const toggleSidebar = () => $("app")?.classList.toggle("sidebar-collapsed");
  if ($("toggle-sidebar")) $("toggle-sidebar").onclick = toggleSidebar;
  if ($("expand-sidebar")) $("expand-sidebar").onclick = toggleSidebar;

  const msgBox = $("messages");
  if (msgBox) {
    new MutationObserver(syncEmpty).observe(msgBox, { childList: true });
    msgBox.addEventListener("scroll", updateStick);
    const ensureCopyIcons = (btn) => {
      if (!btn || btn.querySelector(".icon-tick")) return;
      const curIcon = btn.querySelector(".icon");
      if (curIcon) curIcon.classList.add("icon-copy");
      btn.appendChild(icon("tick", "icon-tick"));
    };

    document.querySelectorAll(".code-copy, .fv-copy").forEach(ensureCopyIcons);

    msgBox.addEventListener("pointerenter", (e) => {
      const btn = e.target.closest?.(".code-copy");
      if (btn) ensureCopyIcons(btn);
    }, true);

    // 代码块复制按钮委托
    msgBox.addEventListener("click", (e) => {
      const btn = e.target.closest(".code-copy");
      if (!btn) return;
      const pre = btn.closest(".code-block")?.querySelector("pre");
      if (!pre) return;
      ensureCopyIcons(btn);
      btn.classList.add("copied");
      btn.title = "已复制";
      clearTimeout(btn._copyTimer);
      btn._copyTimer = setTimeout(() => {
        btn.classList.remove("copied");
        btn.title = "复制代码";
      }, 1800);
      navigator.clipboard.writeText(pre.innerText).catch(() => {
        btn.classList.remove("copied");
        btn.title = "复制代码";
      });
    });
  }
  syncEmpty();

  const togglePlan = () => {
    const collapsed = $("plan-panel")?.classList.toggle("hidden");
    $("plan-pill")?.setAttribute("aria-pressed", String(!collapsed));
  };
  if ($("plan-pill")) $("plan-pill").onclick = togglePlan;
  if ($("plan-header")) $("plan-header").onclick = togglePlan;

  if (typeof ResizeObserver !== "undefined") {
    const chromeRO = new ResizeObserver(syncChrome);
    if ($("plan-dock")) chromeRO.observe($("plan-dock"));
    if ($("input-area")) chromeRO.observe($("input-area"));
  }

  if ($("model-btn")) $("model-btn").onclick = () => {
    if ($("model-menu")?.classList.contains("hidden")) {
      openModelMenu();
      refreshModelList();
    } else closeModelMenu(true);
  };
  if ($("effort-btn")) {
    $("effort-btn").onclick = () => openEffortMenu();
    $("effort-btn").addEventListener("pointerenter", (e) => {
      if (e.pointerType !== "touch") openEffortMenu(false);
    });
  }
  $("model-list")?.addEventListener("pointerenter", () => closeEffortMenu());
  $("model-pill")?.addEventListener("keydown", modelMenuKeydown);
  document.addEventListener("click", (e) => {
    if (!e.target.closest("#model-pill")) closeModelMenu();
  });
  document.addEventListener("scroll", (e) => {
    if ($("model-pill")?.contains(e.target)) positionModelMenus();
    else closeModelMenu();
  }, true);
  window.addEventListener("resize", () => closeModelMenu());
  window.addEventListener("focus", () => { if (state.connected) refreshModelList(); });

  if ($("scroll-bottom")) {
    $("scroll-bottom").onclick = () => {
      smoothScrollToBottom();
    };
  }

  if ($("approval-approve")) $("approval-approve").onclick = () => settleApproval(true);
  if ($("approval-reject")) $("approval-reject").onclick = () => settleApproval(false);
  if ($("question-cancel")) $("question-cancel").onclick = () => settleQuestion(null);
  if ($("question-close")) $("question-close").onclick = () => settleQuestion(null);
  if ($("question-submit")) {
    $("question-submit").onclick = () => {
      const v = $("question-custom")?.value.trim();
      if (v) settleQuestion(v);
    };
  }
  if ($("question-custom")) {
    $("question-custom").addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        const v = e.target.value.trim();
        if (v) settleQuestion(v);
      }
    });
  }

  const input = $("input");
  if (input) {
    input.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    });
    input.addEventListener("input", () => {
      input.style.height = "auto";
      input.style.height = Math.min(input.scrollHeight, 180) + "px";
      updateInputState();
    });
  }

  document.addEventListener("keydown", (e) => {
    const qCard = $("question-card");
    if (qCard && !qCard.classList.contains("hidden")) {
      if (e.key === "Escape") { e.preventDefault(); settleQuestion(null); return; }
      if (e.target === $("question-custom")) return;
      const n = parseInt(e.key, 10);
      const btns = $("question-options")?.children || [];
      if (n >= 1 && n <= btns.length) btns[n - 1].click();
      else if (n === btns.length + 1 && $("question-custom-row") && !$("question-custom-row").classList.contains("hidden")) {
        $("question-custom")?.focus();
      }
      return;
    }
    if (e.key === "Escape") {
      closeModelMenu();
      toggleUserMenu(false);
      cancelTurn();
    }
    if ((e.ctrlKey || e.metaKey) && (e.key === "k" || e.key === "K")) {
      e.preventDefault();
      newSession();
    }
  });

  document.addEventListener("scroll", (e) => {
    const el = e.target;
    if (!(el instanceof HTMLElement)) return;
    if (!el.matches(":hover")) return;
    el.classList.add("is-scrolling");
    clearTimeout(el._scrollbarTimer);
    el._scrollbarTimer = setTimeout(() => el.classList.remove("is-scrolling"), 900);
  }, true);

  document.addEventListener("mouseout", (e) => {
    const el = e.target;
    if (!(el instanceof HTMLElement) || !el.classList.contains("is-scrolling")) return;
    if (e.relatedTarget instanceof Node && el.contains(e.relatedTarget)) return;
    clearTimeout(el._scrollbarTimer);
    el.classList.remove("is-scrolling");
  }, true);
}

export async function init() {
  bind();
  updateInputState();
  bindAccountMenu();
  if (!getToken()) {
    goLogin();
    return;
  }
  showUserChip();
  try {
    await connectAndSetup();
  } catch (e) {
    if (!getToken() || state.connectionReplaced) return;
    UI.clearSkeleton("messages");
    addSystem("❌ 初始化失败：" + (e && e.message || e) + "，将自动重连", true);
    scheduleReconnect();
  }
}

if (document.readyState === "loading") {
  window.addEventListener("DOMContentLoaded", init);
} else {
  init();
}
