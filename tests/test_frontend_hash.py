"""前端内容哈希构建回归测试。"""

import hashlib
import re
from pathlib import Path

from deploy.hash_frontend import build_frontend


def write_asset(root: Path, relative: str, content: str | bytes) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        path.write_bytes(content)
    else:
        path.write_text(content, encoding="utf-8")


def only_file(output: Path, pattern: str) -> Path:
    files = list(output.rglob(pattern))
    assert len(files) == 1, (pattern, files)
    return files[0]


def matched_url(pattern: str, text: str) -> str:
    match = re.search(pattern, text)
    assert match is not None
    return match.group(1)


def test_hash_build_rewrites_references_and_tracks_dependencies(tmp_path: Path) -> None:
    source = tmp_path / "frontend"
    output = tmp_path / "built"
    write_asset(source, "index.html", """
<link rel="stylesheet" href="/static/css/base.css">
<script type="module" src="/static/js/index.js"></script>
<a href="/static/admin.html#models">管理</a>
""")
    write_asset(source, "admin.html", "<h1>管理</h1>")
    write_asset(source, "css/base.css", '@font-face{src:url("../assets/font.woff2?#iefix")}')
    write_asset(source, "assets/font.woff2", b"font")
    write_asset(source, "js/dep.js", "export const value = 1;\n")
    write_asset(source, "js/index.js", 'import { value } from "./dep.js";\nconsole.log(value);\n')

    build_frontend(source, output)

    html = (output / "index.html").read_text(encoding="utf-8")
    assert '/static/admin.html#models' in html
    css_name = matched_url(r'href="(/static/css/[^"]+)"', html)
    js_name = matched_url(r'src="(/static/js/[^"]+)"', html)
    css_path = output / css_name.removeprefix("/static/")
    js_path = output / js_name.removeprefix("/static/")
    assert css_path.is_file()
    assert js_path.is_file()
    assert not (output / "css/base.css").exists()
    assert hashlib.sha256(css_path.read_bytes()).hexdigest()[:8] in css_path.name
    assert hashlib.sha256(js_path.read_bytes()).hexdigest()[:8] in js_path.name

    css = css_path.read_text(encoding="utf-8")
    font_url = matched_url(r'url\("([^"]+)"\)', css)
    assert font_url.endswith("#iefix")
    assert (css_path.parent / font_url.split("#", 1)[0]).resolve().is_file()
    dependency_path = only_file(output, "dep.*.js")
    assert dependency_path.name in js_path.read_text(encoding="utf-8")
    dependency_hash = hashlib.sha256(dependency_path.read_bytes()).hexdigest()[:8]
    assert dependency_path.name == f"dep.{dependency_hash}.js"

    write_asset(source, "js/dep.js", "export const value = 2;\n")
    build_frontend(source, output)

    updated_html = (output / "index.html").read_text(encoding="utf-8")
    updated_js_name = matched_url(r'src="(/static/js/[^"]+)"', updated_html)
    updated_js_path = output / updated_js_name.removeprefix("/static/")
    assert updated_js_path.name != js_path.name
    assert not js_path.exists()
    updated_dependency_path = only_file(output, "dep.*.js")
    assert updated_dependency_path.name != dependency_path.name
    assert updated_dependency_path.name in updated_js_path.read_text(encoding="utf-8")


def test_circular_modules_change_hashes_together(tmp_path: Path) -> None:
    source = tmp_path / "frontend"
    output = tmp_path / "built"
    write_asset(source, "a.js", 'import "./b.js";\nexport const a = 1;\n')
    write_asset(source, "b.js", 'import "./a.js";\nexport const b = 1;\n')

    build_frontend(source, output)
    first_a = only_file(output, "a.*.js")
    first_b = only_file(output, "b.*.js")
    assert first_b.name in first_a.read_text(encoding="utf-8")
    assert first_a.name in first_b.read_text(encoding="utf-8")

    write_asset(source, "b.js", 'import "./a.js";\nexport const b = 2;\n')
    build_frontend(source, output)
    second_a = only_file(output, "a.*.js")
    second_b = only_file(output, "b.*.js")
    assert second_a.name != first_a.name
    assert second_b.name != first_b.name
    assert second_b.name in second_a.read_text(encoding="utf-8")
    assert second_a.name in second_b.read_text(encoding="utf-8")


def test_dynamic_imports_keep_original_aliases(tmp_path: Path) -> None:
    source = tmp_path / "frontend"
    output = tmp_path / "built"
    write_asset(
        source,
        "js/loader.js",
        'const load = (lang, name) => import(`../vendor/prism-${lang}.min.js`);\n'
        'const icon = (name) => `/static/assets/providers/${name}.svg`;\n',
    )
    write_asset(source, "vendor/prism-a.min.js", "window.Prism = {};\n")
    write_asset(source, "assets/providers/a.svg", "<svg></svg>")

    hashed, aliases = build_frontend(source, output)

    assert aliases == 2
    loader = only_file(output, "loader.*.js").read_text(encoding="utf-8")
    assert "../vendor/prism-${lang}.min.js" in loader
    assert "/static/assets/providers/${name}.svg" in loader
    assert (output / "vendor/prism-a.min.js").is_file()
    assert (output / "assets/providers/a.svg").is_file()
    assert only_file(output, "prism-a.min.*.js").is_file()
    assert only_file(output, "a.*.svg").is_file()
    assert hashed == 3


def test_minifies_before_hashing_and_preserves_vendor_files(tmp_path: Path) -> None:
    source = tmp_path / "frontend"
    output = tmp_path / "built"
    write_asset(source, "index.html", "<!-- keep --><div> A B </div>")
    write_asset(source, "js/app.js", """
// build comment
export function add(left, right) {
  const message = "a // b";
  return left + right;
}
""")
    write_asset(source, "css/app.css", """
/* build comment */
:root { --gap: 8px; }
body { color: red; }
""")
    vendor_source = "/*! third-party license */\nwindow.Vendor = true;\n"
    write_asset(source, "vendor/keep.js", vendor_source)

    build_frontend(source, output)

    javascript_path = only_file(output, "app.*.js")
    css_path = only_file(output, "app.*.css")
    vendor_path = only_file(output, "keep.*.js")
    javascript = javascript_path.read_text(encoding="utf-8")
    css = css_path.read_text(encoding="utf-8")
    assert "build comment" not in javascript
    assert "left+right" in javascript
    assert '"a // b"' in javascript
    assert "\n" not in javascript
    assert "build comment" not in css
    assert "body{color:red}" in css
    assert "\n" not in css
    assert vendor_path.read_text(encoding="utf-8") == vendor_source
    assert (output / "index.html").read_text(encoding="utf-8") == "<!-- keep --><div> A B </div>"
    for path in (javascript_path, css_path, vendor_path):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()[:8]
        assert f".{digest}." in path.name
