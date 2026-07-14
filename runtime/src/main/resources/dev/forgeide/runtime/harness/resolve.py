#!/usr/bin/env python3
"""ForgeIDE bundled fallback resolver (T41).

Runs only when a harness ships no resolver of its own (no .gigacode/hooks/resolve_hook_paths.py) —
forge-native harnesses bring their own and ForgeIDE runs that instead. Mirrors its core: read the
placeholder-bearing template .gigacode/hooks/settings.hooks.json, expand ${PROJECT_ROOT} to the real
project root and ${PYTHON} to this interpreter, and write the resolved hooks block into
.gigacode/settings.json (the file the agent runtime reads). Other sections of an existing
settings.json are preserved. No-op (exit 0) when there is no template to resolve.
"""
import json
import sys
from pathlib import Path

PROJECT_ROOT = "${PROJECT_ROOT}"
PYTHON = "${PYTHON}"


def _python_cmd() -> str:
    exe = sys.executable or "python3"
    return f'"{exe}"' if " " in exe else exe


def _expand(node, project_root: str, python_cmd: str):
    if isinstance(node, str):
        return node.replace(PROJECT_ROOT, project_root).replace(PYTHON, python_cmd)
    if isinstance(node, dict):
        return {k: _expand(v, project_root, python_cmd) for k, v in node.items()}
    if isinstance(node, list):
        return [_expand(item, project_root, python_cmd) for item in node]
    return node


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: resolve.py <project>")
        return 1

    # Use the path as ForgeIDE passes it (already absolute) without .resolve() — canonicalizing
    # symlinks here would make the generated paths disagree with what the runtime/preflight see
    # (e.g. macOS /var vs /private/var).
    project = Path(sys.argv[1])
    harness = project / ".gigacode"
    template = harness / "hooks" / "settings.hooks.json"
    target = harness / "settings.json"

    if not template.is_file():
        # Nothing to resolve — the (bundled) preflight will report a missing template if that is
        # actually a problem; a harness without a hooks template is not this script's business.
        return 0

    try:
        tmpl = json.loads(template.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"cannot read template {template}: {exc}")
        return 1

    resolved_hooks = _expand(tmpl.get("hooks", {}), str(project), _python_cmd())

    existing = {}
    if target.is_file():
        try:
            existing = json.loads(target.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            existing = {}

    existing["hooks"] = resolved_hooks
    existing.setdefault("disableAllHooks", False)
    existing.setdefault("$version", 3)

    try:
        target.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as exc:
        print(f"cannot write {target}: {exc}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
