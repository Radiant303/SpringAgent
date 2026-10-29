/**
 * 个人知识库只读查看
 */

import { $ } from "../../utils/dom.js";
import { api, apiText } from "../../api/client.js";
import { UI } from "../../components/ui.js";
import { setMarkdown } from "../../utils/markdown.js";

const DOCUMENTS = [
  { type: "declarative", title: "陈述性知识", description: "是什么：概念、事实与已确认的理解。" },
  { type: "procedural", title: "程序性知识", description: "怎么做：方法、步骤与实践经验。" },
  { type: "conditional", title: "条件性知识", description: "何时与为什么：适用条件、原因与判断依据。" },
];

let activeType = DOCUMENTS[0].type;
let documents = new Map();
let generation = 0;

function definition(type) {
  return DOCUMENTS.find((document) => document.type === type) || DOCUMENTS[0];
}

function setControlsDisabled(disabled) {
  if ($("knowledge-refresh")) $("knowledge-refresh").disabled = disabled;
  document.querySelectorAll("#knowledge-tabs .knowledge-tab").forEach((tab) => {
    tab.disabled = disabled;
  });
}

function showMessage(message) {
  const content = $("knowledge-content");
  const empty = $("knowledge-empty");
  if (content) {
    content.innerHTML = "";
    content.classList.add("hidden");
  }
  if (empty) {
    empty.textContent = message;
    empty.classList.remove("hidden");
  }
}

function renderTabs() {
  const current = definition(activeType);
  document.querySelectorAll("#knowledge-tabs .knowledge-tab").forEach((tab) => {
    const selected = tab.dataset.type === current.type;
    tab.classList.toggle("active", selected);
    tab.setAttribute("aria-selected", String(selected));
    tab.tabIndex = selected ? 0 : -1;
  });
  if ($("knowledge-description")) $("knowledge-description").textContent = current.description;
  $("knowledge-content")?.setAttribute("aria-labelledby", `knowledge-tab-${current.type}`);
}

function beginLoad() {
  const currentGeneration = ++generation;
  setControlsDisabled(true);
  $("knowledge-empty")?.classList.add("hidden");
  const content = $("knowledge-content");
  if (content) {
    content.classList.remove("hidden");
    content.setAttribute("aria-busy", "true");
    content.innerHTML = `
      <div class="sk-block sk-fade knowledge-skeleton" aria-hidden="true">
        <span class="sk" style="--w:38%;--h:24px"></span>
        <span class="sk" style="--w:92%"></span>
        <span class="sk" style="--w:100%"></span>
        <span class="sk" style="--w:76%"></span>
        <span class="sk" style="--w:86%"></span>
      </div>`;
  }
  return currentGeneration;
}

function finishLoad(currentGeneration) {
  if (currentGeneration !== generation) return;
  setControlsDisabled(false);
  $("knowledge-content")?.setAttribute("aria-busy", "false");
}

async function renderActive(currentGeneration) {
  renderTabs();
  const current = definition(activeType);
  const summary = documents.get(current.type);
  if (!summary?.exists) {
    showMessage(`暂无${current.title}，可在与 AI 的对话中确认并记录。`);
    return;
  }

  const text = await apiText(`/api/knowledge/${current.type}`);
  if (currentGeneration !== generation) return;
  if (!text.trim()) {
    showMessage(`暂无${current.title}，可在与 AI 的对话中确认并记录。`);
    return;
  }
  $("knowledge-empty")?.classList.add("hidden");
  const content = $("knowledge-content");
  if (content) {
    content.classList.remove("hidden");
    setMarkdown(content, text, false);
  }
}

async function selectDocument(type, focus = false) {
  if (!DOCUMENTS.some((document) => document.type === type)) return;
  activeType = type;
  if (focus) $(`knowledge-tab-${type}`)?.focus();
  const currentGeneration = beginLoad();
  try {
    await renderActive(currentGeneration);
  } catch (error) {
    if (currentGeneration === generation) {
      showMessage("知识文档加载失败，请重试。");
      UI.toast(error.message, { type: "error" });
    }
  } finally {
    finishLoad(currentGeneration);
  }
}

export async function loadKnowledge() {
  const currentGeneration = beginLoad();
  try {
    const list = await api("/api/knowledge");
    if (currentGeneration !== generation) return;
    const received = new Map((list || []).map((document) => [document.type, document]));
    documents = new Map(DOCUMENTS.map((document) => [document.type, received.get(document.type)]));
    await renderActive(currentGeneration);
  } catch (error) {
    if (currentGeneration === generation) showMessage("知识库加载失败，请点击刷新重试。");
    throw error;
  } finally {
    finishLoad(currentGeneration);
  }
}

export function bindKnowledge() {
  const tabs = $("knowledge-tabs");
  tabs?.addEventListener("click", (event) => {
    const tab = event.target.closest("button[data-type]");
    if (tab) selectDocument(tab.dataset.type);
  });
  tabs?.addEventListener("keydown", (event) => {
    if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const current = DOCUMENTS.findIndex((document) => document.type === activeType);
    let next = current;
    if (event.key === "ArrowLeft") next = (current - 1 + DOCUMENTS.length) % DOCUMENTS.length;
    if (event.key === "ArrowRight") next = (current + 1) % DOCUMENTS.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = DOCUMENTS.length - 1;
    selectDocument(DOCUMENTS[next].type, true);
  });
  if ($("knowledge-refresh")) {
    $("knowledge-refresh").onclick = () => loadKnowledge().catch((error) => {
      UI.toast(error.message, { type: "error" });
    });
  }
}
