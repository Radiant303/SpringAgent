/**
 * 语法高亮工具。
 *
 * @author hanbing
 * @since 2026-10-13
 */

const LANG_DISPLAY = {
  py: "Python",
  python: "Python",
  js: "JavaScript",
  javascript: "JavaScript",
  ts: "TypeScript",
  typescript: "TypeScript",
  jsx: "JSX",
  tsx: "TSX",
  html: "HTML",
  htm: "HTML",
  css: "CSS",
  scss: "SCSS",
  less: "Less",
  json: "JSON",
  sh: "Bash",
  bash: "Bash",
  shell: "Shell",
  zsh: "Zsh",
  sql: "SQL",
  java: "Java",
  c: "C",
  cpp: "C++",
  "c++": "C++",
  cs: "C#",
  csharp: "C#",
  go: "Go",
  golang: "Go",
  rs: "Rust",
  rust: "Rust",
  yaml: "YAML",
  yml: "YAML",
  xml: "XML",
  md: "Markdown",
  markdown: "Markdown",
  php: "PHP",
  rb: "Ruby",
  ruby: "Ruby",
  swift: "Swift",
  kt: "Kotlin",
  kotlin: "Kotlin",
  r: "R",
  dart: "Dart",
  lua: "Lua",
};

/**
 * 格式化语言名称。
 *
 * @param rawLang 原始语言标识
 * @return 语言显示名
 */
export function formatLangName(rawLang) {
  if (!rawLang) return "Code";
  const key = String(rawLang).toLowerCase().trim();
  if (LANG_DISPLAY[key]) return LANG_DISPLAY[key];
  return key.charAt(0).toUpperCase() + key.slice(1);
}

/**
 * 解析语言标识。
 *
 * @param pathOrLang 文件路径或语言标识
 * @return 语言标识
 */
export function hlLang(pathOrLang) {
  const s = String(pathOrLang || "").toLowerCase().trim();
  const ext = (/\.([a-z0-9]+)$/i.exec(s) || [])[1] || s;
  return ext;
}

function escapeHtml(str) {
  return String(str ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

/**
 * 高亮代码文本。
 *
 * @param code 源代码
 * @param langKey 语言标识
 * @return 高亮后的 HTML
 */
export function highlightHtml(code, langKey) {
  const text = String(code ?? "");
  const hljs = typeof window !== "undefined" ? window.hljs : null;
  if (!hljs) return escapeHtml(text);

  const lang = hlLang(langKey);
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(text, { language: lang, ignoreIllegals: true }).value;
    }
    if (text.trim()) {
      return hljs.highlightAuto(text).value;
    }
    return escapeHtml(text);
  } catch {
    return escapeHtml(text);
  }
}

/**
 * 高亮单行代码。
 *
 * @param text 单行代码
 * @param langKey 语言标识
 * @return DOM 片段
 */
export function highlightLine(text, langKey) {
  const frag = document.createDocumentFragment();
  const html = highlightHtml(text, langKey);
  const temp = document.createElement("template");
  temp.innerHTML = html;
  frag.appendChild(temp.content);
  return frag;
}
