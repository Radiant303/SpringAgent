"""HTML、JavaScript 与 CSS 源码语法解析。"""

from collections.abc import Iterable
from typing import TypedDict

import html5lib
import tinycss2
import tree_sitter_javascript
from html5lib.constants import E as HTML_ERROR_MESSAGES
from tinycss2.ast import AtRule, Declaration, Node, ParseError, QualifiedRule
from tree_sitter import Language, Parser


class SyntaxIssue(TypedDict):
    """语法问题的位置与原因。

    @author hanbing
    @since 2026-10-26
    """

    line: int
    column: int
    reason: str


class SyntaxValidationResult(TypedDict):
    """源码语法校验结果。

    @author hanbing
    @since 2026-10-26
    """

    valid: bool
    error_count: int
    errors: list[SyntaxIssue]


_JS_LANGUAGE = Language(tree_sitter_javascript.language())
_CSS_BLOCK_AT_RULES = {
    "-webkit-keyframes",
    "container",
    "counter-style",
    "font-face",
    "font-feature-values",
    "font-palette-values",
    "keyframes",
    "layer",
    "media",
    "nest",
    "page",
    "position-try",
    "property",
    "scope",
    "starting-style",
    "supports",
}


def _result(errors: list[SyntaxIssue]) -> SyntaxValidationResult:
    errors.sort(key=lambda issue: (issue["line"], issue["column"]))
    return {"valid": not errors, "error_count": len(errors), "errors": errors}


def validate_html_source(content: str) -> SyntaxValidationResult:
    parser = html5lib.HTMLParser()
    parser.parse(content)
    errors: list[SyntaxIssue] = []
    for (line, column), code, data in parser.errors:
        template = HTML_ERROR_MESSAGES.get(code) or code
        reason = template % (data or {})
        errors.append({"line": line, "column": column, "reason": reason})
    return _result(errors)


def _javascript_location(content: bytes, line_starts: list[int], row: int, byte: int) -> tuple[int, int]:
    line_start = line_starts[row]
    column = len(content[line_start:byte].decode("utf-8")) + 1
    return row + 1, column


def validate_javascript_source(content: str) -> SyntaxValidationResult:
    encoded = content.encode("utf-8")
    line_starts = [0]
    line_starts.extend(index + 1 for index, char in enumerate(encoded) if char == 10)
    tree = Parser(_JS_LANGUAGE).parse(encoded)
    errors: list[SyntaxIssue] = []
    pending = [tree.root_node]
    while pending:
        node = pending.pop()
        line, column = _javascript_location(encoded, line_starts, node.start_point.row, node.start_byte)
        if node.is_error:
            snippet = encoded[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
            snippet = " ".join(snippet.split())
            if len(snippet) > 120:
                snippet = snippet[:117] + "..."
            reason = f"无法解析的语法片段：{snippet}" if snippet else "存在无法解析的语法"
            errors.append({"line": line, "column": column, "reason": reason})
            continue
        if node.is_missing:
            errors.append({"line": line, "column": column, "reason": f"缺少语法元素：{node.type}"})
        pending.extend(reversed(node.children))
    return _result(errors)


def _css_issue(node: ParseError) -> SyntaxIssue:
    reason = node.message
    if reason == "Stop token reached before {} block for a qualified rule.":
        reason = "规则缺少大括号，或声明缺少冒号"
    return {"line": node.source_line, "column": node.source_column, "reason": reason}


def _collect_css_components(nodes: Iterable[Node], errors: list[SyntaxIssue]) -> None:
    for node in nodes:
        if isinstance(node, ParseError):
            errors.append(_css_issue(node))
            continue
        for attribute in ("content", "arguments"):
            nested = getattr(node, attribute, None)
            if nested:
                _collect_css_components(nested, errors)


def _collect_css_rules(nodes: Iterable[Node], errors: list[SyntaxIssue]) -> None:
    for node in nodes:
        if isinstance(node, ParseError):
            errors.append(_css_issue(node))
        elif isinstance(node, QualifiedRule):
            _collect_css_components(node.prelude, errors)
            nested = tinycss2.parse_blocks_contents(node.content, skip_comments=True, skip_whitespace=True)
            _collect_css_rules(nested, errors)
        elif isinstance(node, AtRule):
            _collect_css_components(node.prelude, errors)
            if node.content is None:
                continue
            if node.lower_at_keyword in _CSS_BLOCK_AT_RULES:
                nested = tinycss2.parse_blocks_contents(node.content, skip_comments=True, skip_whitespace=True)
                _collect_css_rules(nested, errors)
            else:
                _collect_css_components(node.content, errors)
        elif isinstance(node, Declaration):
            _collect_css_components(node.value, errors)


def _unclosed_css_delimiters(content: str, rules: Iterable[Node]) -> str:
    serialized = tinycss2.serialize(rules)
    if not serialized.startswith(content):
        return ""
    suffix = serialized[len(content):]
    return suffix if suffix and all(char in "})]" for char in suffix) else ""


def validate_css_source(content: str) -> SyntaxValidationResult:
    rules = tinycss2.parse_stylesheet(content)
    errors: list[SyntaxIssue] = []
    _collect_css_rules(rules, errors)
    if suffix := _unclosed_css_delimiters(content, rules):
        normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        errors.append({
            "line": normalized.count("\n") + 1,
            "column": len(normalized.rsplit("\n", 1)[-1]) + 1,
            "reason": f"文件末尾缺少闭合符：{suffix}",
        })
    return _result(errors)
