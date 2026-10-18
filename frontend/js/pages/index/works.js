/**
 * 项目/工作区管理业务逻辑
 */

import { $ } from "../../utils/dom.js";
import { formatSize } from "../../utils/format.js";
import { apiFetch } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { state } from "./state.js";
import { addSystem } from "./chatView.js";
import { openWorkSession, refreshSessionList } from "./sessions.js";
import { clearMessages } from "./history.js";
import { updateInputState } from "./input.js";

export const FOLDER_SVG = '<svg class="sb-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3.5 7a2 2 0 0 1 2-2h3.8l2 2.2h7.2a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/></svg>';

export function updateWorkName() {
  const work = state.works.find((w) => w.work_id === state.workId);
  const nameEl = $("input-ws-name");
  if (nameEl) nameEl.textContent = work ? work.name : "未选择项目";
}

let loadGeneration = 0;

export async function loadWorks() {
  const generation = ++loadGeneration;
  let works;
  try {
    const r = await apiFetch("/api/works");
    if (!r.ok) throw new Error("HTTP " + r.status);
    const body = await r.json();
    works = body.data || [];
  } catch (e) {
    if (generation !== loadGeneration) return;
    UI.clearSkeleton("works");
    updateWorkName();
    addSystem("❌ 项目列表加载失败：" + e.message, true);
    return;
  }
  if (generation !== loadGeneration) return;
  state.works = works;
  const stored = state.workId;
  const found = works.find((w) => w.work_id === stored);
  if (!found) {
    const fallback = works.find((w) => w.default_work) || works[0] || null;
    state.workId = fallback ? fallback.work_id : null;
  }
  if (state.workId) localStorage.setItem("sh.workId", state.workId);
  renderWorks();
  updateWorkName();
}

export function renderWorks() {
  const box = $("works");
  if (!box) return;
  box.textContent = "";
  for (const w of state.works) {
    const item = document.createElement("div");
    item.className = "work-item" + (w.work_id === state.workId ? " active" : "");
    item.title = w.name;
    item.insertAdjacentHTML("beforeend", FOLDER_SVG);

    const name = document.createElement("span");
    name.className = "work-name";
    name.textContent = w.name;
    item.appendChild(name);

    if (w.default_work) {
      const badge = document.createElement("span");
      badge.className = "work-badge";
      badge.textContent = "默认";
      item.appendChild(badge);
    }

    const size = document.createElement("span");
    size.className = "work-size";
    size.textContent = formatSize(w.size_bytes || 0);
    item.appendChild(size);

    const del = document.createElement("button");
    del.className = "work-del";
    del.title = "删除项目（连同目录与数据一起删除）";
    del.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M7 7l10 10M17 7L7 17"/></svg>';
    del.onclick = (e) => {
      e.stopPropagation();
      deleteWork(w);
    };
    item.appendChild(del);

    item.onclick = () => selectWork(w.work_id);
    box.appendChild(item);
  }
}

export async function selectWork(workId) {
  if (workId === state.workId) return;
  state.workId = workId;
  localStorage.setItem("sh.workId", workId);
  renderWorks();
  updateWorkName();
  try {
    await openWorkSession();
  } catch (e) {
    UI.clearSkeleton("session-list");
    UI.clearSkeleton("messages");
    addSystem("❌ 切换项目失败：" + e.message, true);
  }
}

export async function createWork() {
  let created = null;
  const name = await UI.prompt({
    title: "新建项目",
    message: "每个项目拥有独立的工作区目录和会话记录。",
    label: "项目名称",
    placeholder: "例如：my-app",
    okText: "创建",
    validate: (v) => (v.trim() ? null : "请输入项目名称"),
    submit: async (v) => {
      const r = await apiFetch("/api/works", {
        method: "POST",
        body: JSON.stringify({ name: v.trim() }),
      });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(body.message || `新建失败（${r.status}）`);
      created = body.data;
    },
  });
  if (name === null || !created) return;
  await loadWorks();
  await selectWork(created.work_id);
}

export async function deleteWork(work) {
  const ok = await UI.confirm({
    title: "删除项目",
    message: `确定删除「${work.name}」吗？\n删除后工作区与会话数据将被清理，不可恢复。`,
    okText: "删除",
    danger: true,
    submit: async () => {
      const r = await apiFetch(`/api/works/${work.work_id}`, { method: "DELETE" });
      const body = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(body.message || `删除失败（${r.status}）`);
    },
  });
  if (!ok) return;
  UI.toast(`项目「${work.name}」已删除`);
  await loadWorks();
  if (state.workId) {
    await openWorkSession();
  } else {
    state.sessionId = null;
    localStorage.removeItem("sh.sessionId");
    clearMessages();
    await refreshSessionList();
    updateInputState();
  }
}
