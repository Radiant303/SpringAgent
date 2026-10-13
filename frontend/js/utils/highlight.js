/**
 * 代码语法高亮与语言识别
 */

import { el } from "./dom.js";

export const LANG_NAMES = {
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

export function formatLangName(rawLang) {
  if (!rawLang) return "Code";
  const key = String(rawLang).toLowerCase().trim();
  if (LANG_NAMES[key]) return LANG_NAMES[key];
  return key.charAt(0).toUpperCase() + key.slice(1);
}

export function hlLang(pathOrLang) {
  const s = String(pathOrLang || "").toLowerCase().trim();
  const ext = (/\.([a-z0-9]+)$/i.exec(s) || [])[1] || s;
  if (!ext) return "plain";
  if (["html", "htm", "xml", "svg", "vue"].includes(ext)) return "markup";
  if (["css", "scss", "less"].includes(ext)) return "css";
  if (["json"].includes(ext)) return "json";
  if (["sql"].includes(ext)) return "sql";
  if (["py", "python"].includes(ext)) return "python";
  if (["sh", "bash", "zsh", "shell", "yaml", "yml", "toml", "ini"].includes(ext)) return "hash";
  if (["js", "javascript", "ts", "typescript", "jsx", "tsx", "mjs", "cjs", "java", "go", "golang", "rs", "rust", "c", "cpp", "c++", "h", "hpp", "cs", "csharp", "kt", "kotlin", "swift", "php"].includes(ext)) return "c";
  return "plain";
}

const PY_KEYWORDS = new Set(
  "def class import from as return if elif else for while break continue in is not and or pass with yield raise try except finally lambda global nonlocal assert async await print self cls".split(" ")
);

const C_KEYWORDS = new Set((
  "function const let var return if else for while do switch case break continue new class extends " +
  "import export from default async await try catch finally throw typeof instanceof this " +
  "public private protected static void interface implements package struct enum fn mut pub use impl match trait type select defer go chan make"
).split(" "));

const SQL_KEYWORDS = new Set((
  "select from where insert into update set delete join left right inner outer on group by order asc desc having limit offset and or not in is null like as create table drop alter values union distinct case when then else end"
).split(" "));

const HASH_KEYWORDS = new Set(
  "if then else elif fi for while do done in case esac return exit echo cd ls mkdir rm cp mv grep find cat curl chmod sudo export alias true false".split(" ")
);

const BOOLEANS = new Set("true false True False None null undefined nil".split(" "));

const BUILTINS = new Set((
  "len range str int float list dict set tuple open type isinstance enumerate zip map filter any all min max sum abs round super input sorted reversed id dir help " +
  "console log parseInt parseFloat Math JSON Promise document window fetch Array Object String Number Boolean Map Set Error"
).split(" "));

function escapeHtml(str) {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

export function highlightHtml(code, langKey) {
  const text = String(code || "");
  const lang = hlLang(langKey);
  if (lang === "plain") return escapeHtml(text);

  let pattern = null;
  let classify = null;

  if (lang === "python") {
    pattern = /(#.*$)|("""[\s\S]*?"""|'''[\s\S]*?'''|"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_]\w*)\b(?=\s*\()|\b([A-Za-z_]\w*)\b/gm;
    classify = (m) => {
      if (m[1]) return "hl-comment";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-number";
      if (m[4]) {
        const w = m[4];
        if (PY_KEYWORDS.has(w)) return "hl-keyword";
        if (BUILTINS.has(w)) return "hl-builtin";
        return "hl-fn";
      }
      if (m[5]) {
        const w = m[5];
        if (PY_KEYWORDS.has(w)) return "hl-keyword";
        if (BOOLEANS.has(w)) return "hl-boolean";
        if (BUILTINS.has(w)) return "hl-builtin";
      }
      return null;
    };
  } else if (lang === "c") {
    pattern = /(\/\/.*$|\/\*[\s\S]*?\*\/)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`)|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_$][\w$]*)\b(?=\s*\()|\b([A-Za-z_$][\w$]*)\b/gm;
    classify = (m) => {
      if (m[1]) return "hl-comment";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-number";
      if (m[4]) {
        const w = m[4];
        if (C_KEYWORDS.has(w)) return "hl-keyword";
        if (BUILTINS.has(w)) return "hl-builtin";
        return "hl-fn";
      }
      if (m[5]) {
        const w = m[5];
        if (C_KEYWORDS.has(w)) return "hl-keyword";
        if (BOOLEANS.has(w)) return "hl-boolean";
        if (BUILTINS.has(w)) return "hl-builtin";
      }
      return null;
    };
  } else if (lang === "markup") {
    pattern = /(<!--[\s\S]*?-->)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(<\/?[A-Za-z0-9_-]+|\/?>|<!DOCTYPE[^>]*>)|\b([A-Za-z0-9_-]+)(?=\s*=)/gi;
    classify = (m) => {
      if (m[1]) return "hl-comment";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-tag";
      if (m[4]) return "hl-attr";
      return null;
    };
  } else if (lang === "css") {
    pattern = /(\/\*[\s\S]*?\*\/)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(-?\d+(?:\.\d+)?(?:px|em|rem|%|s|ms|vh|vw|deg)?)\b|([#.][\w-]+(?=[^;{}]*\{))|\b([a-z-]+)(?=\s*:)/gi;
    classify = (m) => {
      if (m[1]) return "hl-comment";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-number";
      if (m[4]) return "hl-selector";
      if (m[5]) return "hl-keyword";
      return null;
    };
  } else if (lang === "json") {
    pattern = /("(?:[^"\\]|\\.)*")\s*(?=:)|("(?:[^"\\]|\\.)*")|\b(-?\d+(?:\.\d+)?)\b|\b(true|false|null)\b/g;
    classify = (m) => {
      if (m[1]) return "hl-keyword";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-number";
      if (m[4]) return "hl-boolean";
      return null;
    };
  } else if (lang === "sql") {
    pattern = /(--.*$|\/\*[\s\S]*?\*\/)|('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")|\b(\d+(?:\.\d+)?)\b|\b([A-Za-z_]\w*)\b/gm;
    classify = (m) => {
      if (m[1]) return "hl-comment";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-number";
      if (m[4]) {
        const lower = m[4].toLowerCase();
        if (SQL_KEYWORDS.has(lower)) return "hl-keyword";
        if (BOOLEANS.has(lower)) return "hl-boolean";
      }
      return null;
    };
  } else if (lang === "hash") {
    pattern = /(#.*$)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|\b(\d+(?:\.\d+)?)\b|(\$[A-Za-z0-9_]+|\$\{[^}]+\})|\b([A-Za-z_]\w*)\b/gm;
    classify = (m) => {
      if (m[1]) return "hl-comment";
      if (m[2]) return "hl-string";
      if (m[3]) return "hl-number";
      if (m[4]) return "hl-attr";
      if (m[5]) {
        if (HASH_KEYWORDS.has(m[5])) return "hl-keyword";
        if (BOOLEANS.has(m[5])) return "hl-boolean";
      }
      return null;
    };
  }

  if (!pattern || !classify) return escapeHtml(text);

  pattern.lastIndex = 0;
  let last = 0;
  let result = "";
  let m;

  while ((m = pattern.exec(text))) {
    if (m[0] === "") {
      pattern.lastIndex++;
      continue;
    }
    if (m.index > last) {
      result += escapeHtml(text.slice(last, m.index));
    }
    const cls = classify(m);
    if (cls) {
      result += `<span class="${cls}">${escapeHtml(m[0])}</span>`;
    } else {
      result += escapeHtml(m[0]);
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) {
    result += escapeHtml(text.slice(last));
  }
  return result;
}

export function highlightLine(text, langKey) {
  const frag = document.createDocumentFragment();
  const html = highlightHtml(text, langKey);
  const temp = document.createElement("template");
  temp.innerHTML = html;
  frag.appendChild(temp.content);
  return frag;
}
