/**
 * 登录、注册与重置密码业务逻辑
 */

import { $ } from "../utils/dom.js";
import { getToken, saveSession } from "../api/auth.js";
import { initRipple } from "../components/ripple.js";

initRipple();

const DEFAULT_NEXT = "/static/index.html";

const REASONS = {
  expired: "登录已过期，请重新登录",
  invalid: "登录状态已失效，请重新登录",
  passwordChanged: "密码已修改，请使用新密码重新登录",
  disabled: "账号已被禁用，请联系管理员",
};

const MODES = {
  login: { title: "登录", switchText: "没有账号？", switchLink: "注册", switchHash: "#register", submit: "登录", busy: "登录中…" },
  register: { title: "注册", switchText: "已有账号？", switchLink: "登录", switchHash: "#login", submit: "注册并登录", busy: "注册中…" },
  reset: { title: "重置密码", switchText: "想起密码了？", switchLink: "登录", switchHash: "#login", submit: "重置密码", busy: "重置中…" },
};

function safeNext(raw) {
  if (!raw) return DEFAULT_NEXT;
  try {
    const url = new URL(raw, location.origin);
    if (url.origin !== location.origin) return DEFAULT_NEXT;
    if (!url.pathname.startsWith("/static/") || url.pathname.startsWith("/static/login")) return DEFAULT_NEXT;
    return url.pathname + url.search + url.hash;
  } catch {
    return DEFAULT_NEXT;
  }
}

export function initLoginPage() {
  const params = new URLSearchParams(location.search);
  const next = safeNext(params.get("next"));

  // 已登录直接去目标页
  try {
    if (getToken()) {
      location.replace(next);
      return;
    }
  } catch {}

  const form = $("login-form");
  if (!form) return;

  const inputs = {
    username: $("login-username"),
    password: $("login-password"),
    password2: $("login-password2"),
    email: $("login-email"),
    code: $("login-code"),
    newPassword: $("login-new-password"),
    newPassword2: $("login-new-password2"),
  };
  const sendCodeBtn = $("login-send-code");
  let mode = "login";
  let busy = false;
  let cdTimer = null;
  let mailVerify = false;
  let configLoaded = false;
  let noticePinned = false;

  const needsCode = () => (mode === "register" && mailVerify) || mode === "reset";

  fetch("/api/auth/public-config")
    .then((r) => r.json())
    .then((p) => {
      mailVerify = !!(p && p.data && p.data.mailVerify);
      configLoaded = true;
      if (inputs.username) {
        inputs.username.placeholder = mailVerify ? "请输入用户名或注册邮箱" : "请输入用户名";
      }
      setMode(mode);
    })
    .catch(() => {});

  function setMode(m) {
    mode = MODES[m] ? m : "login";
    if (mode === "reset" && configLoaded && !mailVerify) {
      history.replaceState(null, "", location.pathname + location.search + "#login");
      mode = "login";
    }
    const conf = MODES[mode];
    form.classList.toggle("is-register", mode === "register");
    form.classList.toggle("is-reset", mode === "reset");
    form.classList.toggle("is-code", needsCode());
    $("login-title").textContent = conf.title;
    $("login-switch-text").textContent = conf.switchText;
    $("login-switch-link").textContent = conf.switchLink;
    $("login-switch-link").setAttribute("href", conf.switchHash);
    $("login-submit").textContent = conf.submit;
    $("login-forgot-link").classList.toggle("hidden", !(mode === "login" && mailVerify));
    if (inputs.password) {
      inputs.password.autocomplete = mode === "login" ? "current-password" : "new-password";
    }
    document.title = conf.title + " - SpringAgent";
    if (!noticePinned) showNotice("");
    form.querySelectorAll(".login-eye").forEach((btn) => setRevealed(btn, false));
    showError("");
  }

  const EYE_SVG = (slashed) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"
    stroke-linecap="round" stroke-linejoin="round">${slashed
      ? '<path d="M4 4.5l15.5 15.5"/><path d="M9.8 6A9.9 9.9 0 0 1 12 5.7c5.9 0 9.5 6.3 9.5 6.3a17.5 17.5 0 0 1-3.6 4.2M6.6 7.8A17.6 17.6 0 0 0 2.5 12s3.6 6.3 9.5 6.3c1 0 1.9-.2 2.7-.5"/><path d="M9.7 9.9a2.9 2.9 0 0 0 3.9 3.9"/>'
      : '<path d="M2.5 12S6.1 5.7 12 5.7 21.5 12 21.5 12 17.9 18.3 12 18.3 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.9"/>'}</svg>`;

  function setRevealed(btn, show) {
    const input = $(btn.dataset.eye);
    if (!input) return;
    input.type = show ? "text" : "password";
    btn.innerHTML = EYE_SVG(!show);
    btn.setAttribute("aria-pressed", String(show));
    btn.setAttribute("aria-label", show ? "隐藏密码" : "显示密码");
  }

  function showError(msg, field) {
    $("login-error").textContent = msg || "";
    for (const el of Object.values(inputs)) {
      if (el) el.classList.remove("invalid");
    }
    if (field && inputs[field]) {
      inputs[field].classList.add("invalid");
      inputs[field].focus();
    }
  }

  function showNotice(msg) {
    $("login-notice").textContent = msg || "";
    $("login-notice").classList.toggle("hidden", !msg);
  }

  function setBusy(on) {
    busy = on;
    const btn = $("login-submit");
    btn.disabled = on;
    btn.textContent = on ? MODES[mode].busy : MODES[mode].submit;
  }

  async function post(path, body) {
    let r;
    try {
      r = await fetch(`/api/auth/${path}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
    } catch {
      throw new Error("网络错误，请检查连接后重试");
    }
    const payload = await r.json().catch(() => null);
    if (!r.ok) throw new Error((payload && payload.message) || `请求失败（${r.status}）`);
    return payload ? payload.data : null;
  }

  const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  function validate() {
    const username = inputs.username.value.trim();
    const password = inputs.password.value;
    if (mode === "reset") {
      if (!EMAIL_RE.test(inputs.email.value.trim())) return ["请输入正确的邮箱地址", "email"];
      if (!/^\d{6}$/.test(inputs.code.value.trim())) return ["请输入 6 位数字验证码", "code"];
      if (inputs.newPassword.value.length < 6) return ["密码至少 6 位", "newPassword"];
      if (inputs.newPassword.value !== inputs.newPassword2.value) return ["两次输入的密码不一致", "newPassword2"];
      return null;
    }
    if (!username) return ["请输入用户名", "username"];
    if (!password) return ["请输入密码", "password"];
    if (mode === "register") {
      if (username.length < 2 || username.length > 64) return ["用户名长度需 2~64 个字符", "username"];
      if (password.length < 6) return ["密码至少 6 位", "password"];
      if (password !== inputs.password2.value) return ["两次输入的密码不一致", "password2"];
      if (mailVerify) {
        if (!EMAIL_RE.test(inputs.email.value.trim())) return ["请输入正确的邮箱地址", "email"];
        if (!/^\d{6}$/.test(inputs.code.value.trim())) return ["请输入 6 位数字验证码", "code"];
      }
    }
    return null;
  }

  function startCountdown(seconds) {
    clearInterval(cdTimer);
    let left = seconds;
    sendCodeBtn.disabled = true;
    sendCodeBtn.textContent = left + "s 后重发";
    cdTimer = setInterval(() => {
      left -= 1;
      if (left <= 0) {
        clearInterval(cdTimer);
        cdTimer = null;
        sendCodeBtn.disabled = false;
        sendCodeBtn.textContent = "获取验证码";
      } else {
        sendCodeBtn.textContent = left + "s 后重发";
      }
    }, 1000);
  }

  async function onSendCode() {
    if (sendCodeBtn.disabled) return;
    const email = inputs.email.value.trim();
    if (!EMAIL_RE.test(email)) { showError("请输入正确的邮箱地址", "email"); return; }
    showError("");
    sendCodeBtn.disabled = true;
    sendCodeBtn.textContent = "发送中…";
    try {
      const data = await post("send-code", { email, scene: mode === "reset" ? "reset" : "register" });
      startCountdown((data && data.resendAfterSeconds) || 60);
      inputs.code.focus();
    } catch (err) {
      sendCodeBtn.disabled = false;
      sendCodeBtn.textContent = "获取验证码";
      showError(err.message);
    }
  }

  async function onSubmit(e) {
    e.preventDefault();
    if (busy) return;
    const invalid = validate();
    if (invalid) { showError(invalid[0], invalid[1]); return; }

    showError("");
    setBusy(true);
    if (mode === "reset") {
      try {
        await post("reset-password", {
          email: inputs.email.value.trim(),
          code: inputs.code.value.trim(),
          newPassword: inputs.newPassword.value,
        });
      } catch (err) {
        setBusy(false);
        showError(err.message);
        return;
      }
      setBusy(false);
      history.replaceState(null, "", location.pathname + location.search + "#login");
      setMode("login");
      inputs.username.value = inputs.email.value.trim();
      inputs.password.value = "";
      showNotice("密码已重置，请用新密码登录");
      inputs.password.focus();
      return;
    }

    const username = inputs.username.value.trim();
    const password = inputs.password.value;
    const registering = mode === "register";
    try {
      if (registering) {
        const body = { username, password };
        if (mailVerify) {
          body.email = inputs.email.value.trim();
          body.code = inputs.code.value.trim();
        }
        await post("register", body);
      }
    } catch (err) {
      setBusy(false);
      showError(err.message);
      return;
    }
    try {
      const data = await post("login", { username, password });
      saveSession(data);
      localStorage.removeItem("sa.sessionId");
      location.replace(next);
    } catch (err) {
      setBusy(false);
      if (registering) {
        history.replaceState(null, "", location.pathname + location.search + "#login");
        setMode("login");
        showError("注册成功，但自动登录失败：" + err.message);
      } else {
        showError(err.message);
      }
    }
  }

  form.addEventListener("submit", onSubmit);
  sendCodeBtn.addEventListener("click", onSendCode);
  inputs.code.addEventListener("input", () => {
    inputs.code.value = inputs.code.value.replace(/\D/g, "").slice(0, 6);
  });
  form.addEventListener("input", () => { if ($("login-error").textContent) showError(""); });
  form.addEventListener("click", (e) => {
    const btn = e.target.closest(".login-eye");
    if (btn) setRevealed(btn, btn.getAttribute("aria-pressed") !== "true");
  });
  form.querySelectorAll(".login-eye").forEach((btn) => setRevealed(btn, false));
  inputs.username.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !inputs.password.value) {
      e.preventDefault();
      inputs.password.focus();
    }
  });
  window.addEventListener("hashchange", () => {
    setMode(location.hash.slice(1));
    (mode === "reset" ? inputs.email : inputs.username).focus();
  });

  const reason = REASONS[params.get("reason")];
  if (reason) {
    noticePinned = true;
    showNotice(reason);
  }
  setMode(location.hash.slice(1));
  (mode === "reset" ? inputs.email : inputs.username).focus();
}

// 自动初始化
if (typeof document !== "undefined") {
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initLoginPage);
  } else {
    initLoginPage();
  }
}
