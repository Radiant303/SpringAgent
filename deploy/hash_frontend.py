"""压缩前端资源、生成内容哈希文件名并改写资源引用。"""

from __future__ import annotations

import argparse
import fnmatch
import hashlib
import posixpath
import re
import shutil
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import cast
from urllib.parse import unquote, urlsplit, urlunsplit

from rcssmin import cssmin
from rjsmin import jsmin

HTML_SUFFIXES = {".html", ".htm"}
TEXT_SUFFIXES = HTML_SUFFIXES | {".css", ".js", ".mjs", ".cjs"}
HASH_LENGTH = 8
HTML_URL_RE = re.compile(
    r"\b(?:href|src|poster)\s*=\s*(?P<quote>[\"'])(?P<url>[^\"']*)(?P=quote)",
    re.IGNORECASE,
)
HTML_SRCSET_RE = re.compile(
    r"\b(?:srcset|imagesrcset)\s*=\s*(?P<quote>[\"'])(?P<url>[^\"']*)(?P=quote)",
    re.IGNORECASE,
)
SRCSET_URL_RE = re.compile(r"(?:^|,)\s*(?P<url>[^\s,]+)")
CSS_URL_RE = re.compile(
    r"url\(\s*(?:(?P<quote>[\"'])(?P<quoted>[^\"']*)(?P=quote)|(?P<bare>[^)\"'\s]+))\s*\)",
    re.IGNORECASE,
)
CSS_IMPORT_RE = re.compile(
    r"@import\s+(?P<quote>[\"'])(?P<url>[^\"']+)(?P=quote)",
    re.IGNORECASE,
)
JS_URL_RE = re.compile(r"(?P<quote>[\"'`])(?P<url>(?:/|\./|\.\./)[^\"'`\r\n]*)(?P=quote)")
JS_DYNAMIC_RE = re.compile(r"`(?P<url>(?:/|\./|\.\./)[^`\r\n]*\$\{[^`\r\n]*)`")
TEMPLATE_EXPRESSION_RE = re.compile(r"\$\{.*?\}")


@dataclass
class Reference:
    start: int
    end: int
    target: PurePosixPath


@dataclass
class Asset:
    path: PurePosixPath
    content: bytes
    text: str | None
    references: list[Reference]
    output_path: PurePosixPath | None


def normalize_prefix(prefix: str) -> str:
    if not prefix or prefix == "/":
        return ""
    return "/" + prefix.strip("/") + "/"


def is_html(path: PurePosixPath) -> bool:
    return path.suffix.lower() in HTML_SUFFIXES


def minify_source(path: PurePosixPath, text: str) -> str:
    if path.parts[0] == "vendor":
        return text
    suffix = path.suffix.lower()
    if suffix == ".css":
        return cast(str, cssmin(text))
    if suffix in {".js", ".mjs", ".cjs"}:
        return cast(str, jsmin(text))
    return text


def local_path(
    source_path: PurePosixPath,
    url_path: str,
    prefix: str,
) -> PurePosixPath | None:
    if not url_path:
        return None
    if url_path.startswith("/"):
        relative = url_path[len(prefix):] if prefix and url_path.startswith(prefix) else url_path.lstrip("/")
    else:
        relative = posixpath.join(str(source_path.parent), url_path)
    normalized = posixpath.normpath(relative)
    if normalized in {".", ".."} or normalized.startswith("../"):
        return None
    return PurePosixPath(normalized)


def resolve_url(
    source_path: PurePosixPath,
    url: str,
    assets: dict[PurePosixPath, Asset],
    prefix: str,
) -> PurePosixPath | None:
    parts = urlsplit(url)
    if parts.scheme or parts.netloc:
        return None
    target = local_path(source_path, unquote(parts.path), prefix)
    return target if target in assets else None


def references_for(asset: Asset, assets: dict[PurePosixPath, Asset], prefix: str) -> list[Reference]:
    if asset.text is None:
        return []
    suffix = asset.path.suffix.lower()
    matches: list[tuple[int, int]] = []
    if suffix in HTML_SUFFIXES:
        matches.extend((match.start("url"), match.end("url")) for match in HTML_URL_RE.finditer(asset.text))
        for attribute in HTML_SRCSET_RE.finditer(asset.text):
            base = attribute.start("url")
            matches.extend(
                (base + match.start("url"), base + match.end("url"))
                for match in SRCSET_URL_RE.finditer(attribute.group("url"))
            )
    elif suffix == ".css":
        for match in CSS_URL_RE.finditer(asset.text):
            group = "quoted" if match.group("quoted") is not None else "bare"
            matches.append((match.start(group), match.end(group)))
        matches.extend((match.start("url"), match.end("url")) for match in CSS_IMPORT_RE.finditer(asset.text))
    elif suffix in {".js", ".mjs", ".cjs"}:
        matches.extend(
            (match.start("url"), match.end("url"))
            for match in JS_URL_RE.finditer(asset.text)
            if "${" not in match.group("url")
        )

    references = []
    for start, end in matches:
        target = resolve_url(asset.path, asset.text[start:end], assets, prefix)
        if target is not None:
            references.append(Reference(start, end, target))
    return sorted(references, key=lambda reference: reference.start)


def dynamic_targets(
    asset: Asset,
    assets: dict[PurePosixPath, Asset],
    prefix: str,
) -> set[PurePosixPath]:
    if asset.text is None or asset.path.suffix.lower() not in {".js", ".mjs", ".cjs"}:
        return set()
    targets = set()
    for match in JS_DYNAMIC_RE.finditer(asset.text):
        parts = urlsplit(match.group("url"))
        if parts.scheme or parts.netloc:
            continue
        pattern_path = TEMPLATE_EXPRESSION_RE.sub("*", unquote(parts.path))
        pattern = local_path(asset.path, pattern_path, prefix)
        if pattern is None:
            continue
        targets.update(
            target for target in assets if fnmatch.fnmatchcase(str(target), str(pattern))
        )
    return targets


def hashed_path(path: PurePosixPath, digest: str) -> PurePosixPath:
    suffix = path.suffix
    if not suffix:
        return path.with_name(f"{path.name}.{digest}")
    return path.with_name(f"{path.name[:-len(suffix)]}.{digest}{suffix}")


def replacement_url(
    source_path: PurePosixPath,
    original_url: str,
    target_path: PurePosixPath,
    prefix: str,
) -> str:
    parts = urlsplit(original_url)
    if parts.path.startswith("/"):
        if prefix and parts.path.startswith(prefix):
            path = prefix + str(target_path)
        else:
            path = "/" + str(target_path)
    else:
        path = posixpath.relpath(str(target_path), str(source_path.parent))
        if parts.path.startswith("./") and not path.startswith("."):
            path = "./" + path
    return urlunsplit(("", "", path, parts.query, parts.fragment))


def render(asset: Asset, assets: dict[PurePosixPath, Asset], prefix: str) -> bytes:
    if asset.text is None:
        return asset.content
    pieces = []
    cursor = 0
    for reference in asset.references:
        target = assets[reference.target].output_path
        if target is None:
            raise ValueError(f"资源哈希尚未生成：{reference.target}")
        original_url = asset.text[reference.start:reference.end]
        pieces.append(asset.text[cursor:reference.start])
        pieces.append(replacement_url(asset.path, original_url, target, prefix))
        cursor = reference.end
    pieces.append(asset.text[cursor:])
    return "".join(pieces).encode("utf-8")


def update_digest(digest, value: bytes) -> None:
    digest.update(len(value).to_bytes(8, "big"))
    digest.update(value)


def components_for(
    assets: dict[PurePosixPath, Asset],
) -> tuple[list[list[PurePosixPath]], dict[PurePosixPath, set[PurePosixPath]]]:
    nodes = [path for path, asset in assets.items() if not is_html(path)]
    node_set = set(nodes)
    edges = {
        path: {
            reference.target
            for reference in assets[path].references
            if reference.target in node_set
        }
        for path in nodes
    }
    indices: dict[PurePosixPath, int] = {}
    low_links: dict[PurePosixPath, int] = {}
    stack: list[PurePosixPath] = []
    on_stack: set[PurePosixPath] = set()
    components: list[list[PurePosixPath]] = []

    def visit(node: PurePosixPath) -> None:
        indices[node] = len(indices)
        low_links[node] = indices[node]
        stack.append(node)
        on_stack.add(node)
        for target in edges[node]:
            if target not in indices:
                visit(target)
                low_links[node] = min(low_links[node], low_links[target])
            elif target in on_stack:
                low_links[node] = min(low_links[node], indices[target])
        if low_links[node] == indices[node]:
            component = []
            while True:
                member = stack.pop()
                on_stack.remove(member)
                component.append(member)
                if member == node:
                    break
            components.append(sorted(component))

    for node in nodes:
        if node not in indices:
            visit(node)
    return components, edges


def assign_output_paths(assets: dict[PurePosixPath, Asset], prefix: str) -> None:
    for path, asset in assets.items():
        asset.output_path = path if is_html(path) else None
    components, edges = components_for(assets)
    component_for = {
        path: index for index, component in enumerate(components) for path in component
    }
    dependencies = {
        index: {
            component_for[target]
            for path in component
            for target in edges[path]
            if component_for[target] != index
        }
        for index, component in enumerate(components)
    }
    component_hashes: dict[int, str] = {}
    assigned: set[int] = set()

    def assign(index: int) -> None:
        if index in assigned:
            return
        for dependency in dependencies[index]:
            assign(dependency)
        component = components[index]
        if len(component) == 1 and component[0] not in edges[component[0]]:
            asset = assets[component[0]]
            digest = hashlib.sha256(render(asset, assets, prefix)).hexdigest()[:HASH_LENGTH]
            asset.output_path = hashed_path(asset.path, digest)
        else:
            hasher = hashlib.sha256()
            for path in component:
                update_digest(hasher, str(path).encode("utf-8"))
                update_digest(hasher, assets[path].content)
            for dependency in sorted(dependencies[index]):
                update_digest(hasher, component_hashes[dependency].encode("ascii"))
            digest = hasher.hexdigest()[:HASH_LENGTH]
            for path in component:
                assets[path].output_path = hashed_path(path, digest)
        component_hashes[index] = digest
        assigned.add(index)

    for index in range(len(components)):
        assign(index)


def validate_paths(source: Path, output: Path) -> None:
    if not source.is_dir():
        raise ValueError(f"源目录不存在：{source}")
    if source == output or source.is_relative_to(output) or output.is_relative_to(source):
        raise ValueError("源目录与输出目录不能相同或互相包含")


def build_frontend(
    source_directory: str | Path,
    output_directory: str | Path,
    url_prefix: str = "/static/",
) -> tuple[int, int]:
    source = Path(source_directory).resolve()
    output = Path(output_directory).resolve()
    validate_paths(source, output)
    prefix = normalize_prefix(url_prefix)
    assets = {}
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        relative = PurePosixPath(path.relative_to(source).as_posix())
        content = path.read_bytes()
        text = content.decode("utf-8") if relative.suffix.lower() in TEXT_SUFFIXES else None
        if text is not None:
            text = minify_source(relative, text)
            content = text.encode("utf-8")
        assets[relative] = Asset(relative, content, text, [], relative if is_html(relative) else None)

    aliases = set()
    for asset in assets.values():
        aliases.update(dynamic_targets(asset, assets, prefix))
    for asset in assets.values():
        asset.references = references_for(asset, assets, prefix)
    assign_output_paths(assets, prefix)

    output_files = {}
    for asset in assets.values():
        destinations = [asset.path if is_html(asset.path) or asset.path in aliases else asset.output_path]
        if not is_html(asset.path) and asset.path in aliases:
            destinations.append(asset.output_path)
        for destination in destinations:
            if destination is None:
                raise ValueError(f"资源哈希尚未生成：{asset.path}")
            previous = output_files.get(destination)
            if previous is not None and previous != asset.path:
                raise ValueError(f"输出文件名冲突：{destination}")
            output_files[destination] = asset.path

    if output.is_symlink() or output.is_file():
        output.unlink()
    elif output.is_dir():
        shutil.rmtree(output)
    output.mkdir(parents=True)
    for asset in assets.values():
        content = render(asset, assets, prefix)
        destinations = [asset.path if is_html(asset.path) or asset.path in aliases else asset.output_path]
        if not is_html(asset.path) and asset.path in aliases:
            destinations.append(asset.output_path)
        for destination in destinations:
            target = output.joinpath(*destination.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
    return sum(not is_html(path) for path in assets), len(aliases)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path, default=Path("frontend"), help="前端源目录")
    parser.add_argument("output", nargs="?", type=Path, default=Path("dist/frontend"), help="构建输出目录")
    parser.add_argument("--prefix", default="/static/", help="根路径资源的 URL 前缀")
    args = parser.parse_args()
    try:
        hashed, aliases = build_frontend(args.source, args.output, args.prefix)
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(1, f"前端构建失败：{error}\n")
    print(f"前端构建完成：{hashed} 个哈希资源，{aliases} 个动态加载别名，输出至 {args.output}")


if __name__ == "__main__":
    main()
