/**
 * Prism 语法高亮工具。
 *
 * @author hanbing
 * @since 2026-10-13
 */

import "../../vendor/prism/prism.js";

const LANG_DISPLAY = {
  markup: "Markup",
  html: "HTML",
  htm: "HTML",
  xml: "XML",
  vue: "Vue",
  css: "CSS",
  clike: "C-like",
  js: "JavaScript",
  javascript: "JavaScript",
  ts: "TypeScript",
  typescript: "TypeScript",
  jsx: "JSX",
  tsx: "TSX",
  py: "Python",
  python: "Python",
  sh: "Bash",
  bash: "Bash",
  shell: "Shell",
  zsh: "Zsh",
  c: "C",
  cpp: "C++",
  "c++": "C++",
  cs: "C#",
  csharp: "C#",
  go: "Go",
  golang: "Go",
  rs: "Rust",
  rust: "Rust",
  java: "Java",
  kt: "Kotlin",
  kotlin: "Kotlin",
  dart: "Dart",
  php: "PHP",
  rb: "Ruby",
  ruby: "Ruby",
  swift: "Swift",
  r: "R",
  lua: "Lua",
  scss: "SCSS",
  less: "Less",
  json: "JSON",
  yaml: "YAML",
  yml: "YAML",
  md: "Markdown",
  markdown: "Markdown",
  sql: "SQL",
  diff: "Diff",
  docker: "Dockerfile",
  graphql: "GraphQL",
  haskell: "Haskell",
  ini: "INI",
  latex: "LaTeX",
  makefile: "Makefile",
  matlab: "MATLAB",
  mermaid: "Mermaid",
  nginx: "Nginx",
  objectivec: "Objective-C",
  perl: "Perl",
  powershell: "PowerShell",
  protobuf: "Protocol Buffers",
  scala: "Scala",
  toml: "TOML",
  plaintext: "Plain Text",
};

const BUILTIN_LANGUAGES = new Set(["markup", "css", "clike", "javascript", "plaintext"]);
const LANGUAGE_DEPENDENCIES = {
  cpp: ["c"],
  objectivec: ["c"],
  php: ["markup-templating"],
  scala: ["java"],
  tsx: ["jsx", "typescript"],
};
const COMPONENT_LANGUAGES = new Set([
  "bash", "c", "cpp", "csharp", "dart", "diff", "docker", "go", "graphql",
  "haskell", "ini", "java", "json", "jsx", "kotlin", "latex", "lua", "makefile",
  "less", "markdown", "markup-templating", "matlab", "mermaid", "nginx", "objectivec",
  "perl", "php", "powershell", "protobuf", "python", "r", "ruby", "rust", "scala",
  "scss", "sql", "swift", "toml", "tsx", "typescript", "yaml",
]);
const aliases = {
  html: "markup",
  htm: "markup",
  xml: "markup",
  vue: "markup",
  js: "javascript",
  ts: "typescript",
  shell: "bash",
  sh: "bash",
  zsh: "bash",
  "c++": "cpp",
  cc: "cpp",
  cplusplus: "cpp",
  cs: "csharp",
  "c#": "csharp",
  dockerfile: "docker",
  golang: "go",
  hs: "haskell",
  kt: "kotlin",
  tex: "latex",
  mk: "makefile",
  md: "markdown",
  objc: "objectivec",
  py: "python",
  rb: "ruby",
  rs: "rust",
  yml: "yaml",
  text: "plaintext",
  txt: "plaintext",
};
const languagePromises = new Map();
const prism = typeof window !== "undefined" ? window.Prism : null;

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

export function getHighlightLang(pathOrLang) {
  const key = hlLang(pathOrLang);
  const lang = Object.hasOwn(aliases, key) ? aliases[key] : key;
  return BUILTIN_LANGUAGES.has(lang) || COMPONENT_LANGUAGES.has(lang) ? lang : null;
}

function isHighlightLoaded(lang) {
  return !!prism && Object.hasOwn(prism.languages, lang);
}

function loadLanguage(lang) {
  if (isHighlightLoaded(lang)) return Promise.resolve();
  if (languagePromises.has(lang)) return languagePromises.get(lang);
  const promise = (async () => {
    for (const dependency of LANGUAGE_DEPENDENCIES[lang] || []) await loadLanguage(dependency);
    await import(`../../vendor/prism/components/prism-${lang}.min.js`);
  })().catch(() => {});
  languagePromises.set(lang, promise);
  return promise;
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
  const lang = getHighlightLang(langKey);
  if (!lang || !isHighlightLoaded(lang)) return escapeHtml(text);
  try {
    return prism.highlight(text, prism.languages[lang], lang);
  } catch {
    return escapeHtml(text);
  }
}

/**
 * 高亮代码节点，并在语言包加载完成后重绘。
 *
 * @param node 代码节点
 * @param text 源代码
 * @param langKey 语言标识
 */
export function highlightElement(node, text, langKey) {
  const lang = getHighlightLang(langKey);
  node.innerHTML = highlightHtml(text, langKey);
  if (!lang || isHighlightLoaded(lang)) return;
  loadLanguage(lang).then(() => {
    if (!node.isConnected || !isHighlightLoaded(lang)) return;
    node.innerHTML = highlightHtml(node.textContent, lang);
  });
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
  const node = document.createElement("span");
  highlightElement(node, text, langKey);
  frag.appendChild(node);
  return frag;
}
