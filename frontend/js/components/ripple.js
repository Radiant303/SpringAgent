/**
 * 按钮点击水波纹
 */

const TARGET_SELECTOR = "button, .nav-item";

function allowed(target) {
  const host = target.closest(TARGET_SELECTOR);
  if (!host || host.disabled || host.getAttribute("aria-disabled") === "true") return null;
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return null;
  return host;
}

function ripple(host, x, y) {
  const rect = host.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height) * 2;
  host.classList.add("ripple-host");
  host.querySelectorAll(".ripple-ink").forEach((ink) => ink.remove());
  const ink = document.createElement("span");
  ink.className = "ripple-ink";
  ink.setAttribute("aria-hidden", "true");
  ink.style.width = `${size}px`;
  ink.style.height = `${size}px`;
  ink.style.left = `${x - size / 2}px`;
  ink.style.top = `${y - size / 2}px`;
  ink.addEventListener("animationend", () => ink.remove(), { once: true });
  host.appendChild(ink);
  setTimeout(() => ink.remove(), 700);
}

export function initRipple() {
  document.addEventListener("pointerdown", (event) => {
    if (event.button !== 0) return;
    const host = allowed(event.target);
    if (!host) return;
    const rect = host.getBoundingClientRect();
    ripple(host, event.clientX - rect.left, event.clientY - rect.top);
  });

  document.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" && event.key !== " ") return;
    const host = allowed(event.target);
    if (!host) return;
    const rect = host.getBoundingClientRect();
    ripple(host, rect.width / 2, rect.height / 2);
  });
}
