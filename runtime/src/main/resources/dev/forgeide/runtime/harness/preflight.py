#!/usr/bin/env python3
"""ForgeIDE harness preflight (SDD FR-1.4).

Verifies a project's deployed harness is actually enforceable before a run may start:
the .gigacode/ directory and settings.hooks.json must exist, settings.hooks.json must be
valid JSON, and every *.py/*.sh path it references must exist on disk. Exit 0 means
enforcement is on; any non-zero exit means "enforcement off", with the reason on stdout.
"""
import json
import sys
from pathlib import Path


def _walk_strings(node):
    if isinstance(node, str):
        yield node
    elif isinstance(node, dict):
        for value in node.values():
            yield from _walk_strings(value)
    elif isinstance(node, list):
        for value in node:
            yield from _walk_strings(value)


def _check(project: Path) -> list[str]:
    harness = project / ".gigacode"
    settings = harness / "settings.hooks.json"
    problems = []

    if not harness.is_dir():
        problems.append(f"missing harness directory: {harness}")
        return problems
    if not settings.is_file():
        legacy = harness / "hooks" / "settings.hooks.json"
        if legacy.is_file():
            # T32: older imports wrote settings.hooks.json under hooks/ instead of the harness
            # root, where this script (and the hash-manifest) actually look for it.
            problems.append(
                f"{settings} not found, but found {legacy} — this project was imported "
                f"before settings.hooks.json moved to the harness root; move it to {settings} "
                "to fix (see docs/tasks/T32-hooks-path-unification.md)"
            )
        else:
            problems.append(f"missing {settings}")
        return problems

    try:
        config = json.loads(settings.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        problems.append(f"{settings} is not valid JSON: {exc}")
        return problems

    for value in _walk_strings(config):
        # T38: a hook reference is routinely a whole command line ("python3 hooks/tdd-guard.py"),
        # not a bare path, so split on whitespace and check each token that looks like a script —
        # checking the unsplit string used to reject valid configs (".gigacode/python3 hooks/..."
        # is not a file) and made every freshly imported project fail preflight.
        for raw in value.split():
            # T40: hook commands routinely template the project root as ${PROJECT_ROOT}
            # ("${PYTHON} ${PROJECT_ROOT}/.gigacode/hooks/guard.py") — expand it to the real project
            # path first, or the token stays "relative" (starts with "${") and resolves under
            # .gigacode/ to a path that can never exist, failing a harness whose files are all there.
            token = raw.replace("${PROJECT_ROOT}", str(project))
            if not (token.endswith(".py") or token.endswith(".sh")):
                continue
            if "${" in token:
                # An unresolved placeholder we don't own (e.g. ${PYTHON} glued into the path) — the
                # harness expands it at run time; we can't resolve it, so don't flag a false miss.
                continue
            candidate = Path(token)
            # Hook paths in settings.hooks.json are relative to the harness directory itself
            # (.gigacode/), the same root the hash-manifest (SR-8) hashes hooks/skills under.
            hook_path = candidate if candidate.is_absolute() else harness / token
            if not hook_path.is_file():
                problems.append(f"hook referenced in settings.hooks.json not found: {raw}")

    return problems


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: preflight.py <project>")
        return 1

    problems = _check(Path(sys.argv[1]))
    if problems:
        for problem in problems:
            print(problem)
        return 1

    print("preflight: ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
