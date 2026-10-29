/**
 * 知识文档目录与标题导航
 */

import { $, el } from "../../utils/dom.js";

let headings = [];
let links = new Map();
let toggles = new Map();
let scrollScheduled = false;

function setGroupExpanded(group, expanded) {
  group.hidden = !expanded;
  const toggle = toggles.get(group.id);
  if (!toggle) return;
  toggle.setAttribute("aria-expanded", String(expanded));
  toggle.setAttribute("aria-label", `${expanded ? "折叠" : "展开"} ${toggle.dataset.title}`);
}

function reveal(link) {
  let current = link.parentElement;
  const nav = $("knowledge-toc-nav");
  while (current && current !== nav) {
    if (current.tagName === "UL" && current.classList.contains("knowledge-toc-children")) {
      setGroupExpanded(current, true);
    }
    current = current.parentElement;
  }
}

function setActive(id) {
  for (const [headingId, link] of links) {
    const active = headingId === id;
    link.classList.toggle("active", active);
    link.parentElement.classList.toggle("active", active);
    if (active) link.setAttribute("aria-current", "location");
    else link.removeAttribute("aria-current");
  }
  const active = links.get(id);
  if (active) reveal(active);
}

function syncActive() {
  scrollScheduled = false;
  if (!headings.length) return;
  const content = $("knowledge-content");
  const threshold = (content ? content.getBoundingClientRect().top : 0) + 32;
  let current = headings[0].id;
  for (const heading of headings) {
    if (heading.getBoundingClientRect().top > threshold) break;
    current = heading.id;
  }
  setActive(current);
}

function scheduleActive() {
  if (scrollScheduled) return;
  scrollScheduled = true;
  requestAnimationFrame(syncActive);
}

function uniqueId(text, used) {
  const normalized = text.normalize("NFKC").toLowerCase().trim()
    .replace(/[^\p{L}\p{N}]+/gu, "-").replace(/^-+|-+$/g, "") || "section";
  const base = `knowledge-${normalized}`;
  const next = (used.get(base) || 0) + 1;
  used.set(base, next);
  return next === 1 ? base : `${base}-${next}`;
}

function buildTree() {
  const root = { children: [] };
  const stack = [{ level: 0, node: root }];
  for (const heading of headings) {
    const level = Number(heading.tagName.slice(1));
    const node = { heading, children: [] };
    while (stack.at(-1).level >= level) stack.pop();
    stack.at(-1).node.children.push(node);
    stack.push({ level, node });
  }
  return root.children;
}

function renderNodes(nodes, container) {
  for (const node of nodes) {
    const heading = node.heading;
    const text = heading.textContent.trim();
    const item = el("li", "knowledge-toc-item");
    const row = el("div", "knowledge-toc-row");
    let toggle;
    if (node.children.length) {
      toggle = el("button", "knowledge-toc-toggle");
      toggle.type = "button";
      toggle.dataset.title = text;
      toggle.setAttribute("aria-expanded", "true");
      toggle.setAttribute("aria-label", `折叠 ${text}`);
    } else {
      toggle = el("span", "knowledge-toc-spacer");
      toggle.setAttribute("aria-hidden", "true");
    }
    row.appendChild(toggle);

    const link = el("a", "knowledge-toc-link", text);
    link.href = "#knowledge";
    link.dataset.heading = heading.id;
    links.set(heading.id, link);
    row.appendChild(link);
    item.appendChild(row);

    if (node.children.length) {
      const group = el("ul", "knowledge-toc-children");
      group.id = `${heading.id}-children`;
      toggle.setAttribute("aria-controls", group.id);
      toggles.set(group.id, toggle);
      renderNodes(node.children, group);
      item.appendChild(group);
    }
    container.appendChild(item);
  }
}

export function clearKnowledgeToc() {
  headings = [];
  links.clear();
  toggles.clear();
  document.querySelector(".knowledge-layout")?.classList.add("no-toc");
  $("knowledge-toc")?.classList.add("hidden");
  if ($("knowledge-toc-nav")) $("knowledge-toc-nav").innerHTML = "";
}

export function renderKnowledgeToc() {
  clearKnowledgeToc();
  const content = $("knowledge-content");
  const toc = $("knowledge-toc");
  const nav = $("knowledge-toc-nav");
  if (!content || !toc || !nav) return;

  headings = [...content.querySelectorAll("h1, h2, h3, h4")];
  const used = new Map();
  for (const heading of headings) heading.id = uniqueId(heading.textContent.trim(), used);
  const tree = buildTree();
  const root = el("ul", "knowledge-toc-root");
  renderNodes(tree, root);
  nav.appendChild(root);
  document.querySelector(".knowledge-layout")?.classList.toggle("no-toc", headings.length === 0);
  toc.classList.toggle("hidden", headings.length === 0);
  syncActive();
}

export function bindKnowledgeToc() {
  const toc = $("knowledge-toc");
  toc?.addEventListener("click", (event) => {
    const toggle = event.target.closest("button[aria-controls]");
    if (toggle) {
      const group = document.getElementById(toggle.getAttribute("aria-controls"));
      if (group) setGroupExpanded(group, toggle.getAttribute("aria-expanded") !== "true");
      return;
    }

    const link = event.target.closest("a[data-heading]");
    if (!link) return;
    event.preventDefault();
    const heading = document.getElementById(link.dataset.heading);
    if (!heading) return;
    setActive(heading.id);
    const reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    heading.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "start" });
  });

  $("knowledge-content")?.addEventListener("scroll", scheduleActive, { passive: true });
  $("admin-main")?.addEventListener("scroll", scheduleActive, { passive: true });
  window.addEventListener("scroll", scheduleActive, { passive: true });
}
