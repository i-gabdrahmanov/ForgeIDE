#!/usr/bin/env python3
"""ForgeIDE bundled harness preflight (SDD FR-1.4, T41 forge layout).

Fallback validator, used only when a harness ships no preflight of its own — forge-native
harnesses bring .gigacode/hooks/preflight.py and ForgeIDE runs that instead. Verifies a deployed
harness is actually enforceable before a run may start: the .gigacode/ directory and the
hooks/settings.hooks.json template must exist and be valid JSON, the resolved settings.json (what
the agent runtime reads) must have been generated and be valid JSON, and every *.py/*.sh path it
references must exist on disk. Exit 0 means enforcement is on; any non-zero exit means "enforcement
off", with the reason on stdout.
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
    template = harness / "hooks" / "settings.hooks.json"
    resolved = harness / "settings.json"
    problems = []

    if not harness.is_dir():
        problems.append(f"missing harness directory: {harness}")
        return problems

    # T41: the template lives under hooks/ (forge layout). A stray copy at the harness root is the
    # old ForgeIDE T32 placement — deploy's healLayout relocates it, so this only fires if that
    # didn't run.
    if not template.is_file():
        legacy = harness / "settings.hooks.json"
        if legacy.is_file():
            problems.append(
                f"{template} not found, but found {legacy} — settings.hooks.json belongs under "
                "hooks/ (forge layout); re-deploy to relocate it"
            )
        else:
            problems.append(f"missing hooks template: {template} — hooks not deployed")
        return problems

    try:
        json.loads(template.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        problems.append(f"{template} is not valid JSON: {exc}")
        return problems

    # The resolved settings.json is what the agent runtime actually reads; deploy generates it from
    # the template before this runs. Its absence means resolution never happened → enforcement off.
    if not resolved.is_file():
        problems.append(f"missing resolved config: {resolved} — deploy did not generate it from the template")
        return problems

    try:
        config = json.loads(resolved.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        problems.append(f"{resolved} is not valid JSON: {exc}")
        return problems

    for value in _walk_strings(config):
        # A hook reference is routinely a whole command line ("python3 hooks/tdd-guard.py" or, after
        # resolution, "<python> /abs/.gigacode/hooks/guard.py"), not a bare path — split on
        # whitespace and check each token that looks like a script.
        for raw in value.split():
            # T40 safety net: even the resolved config may carry ${PROJECT_ROOT} if a resolver
            # missed it — expand it here too so the token isn't treated as "relative".
            token = raw.replace("${PROJECT_ROOT}", str(project))
            if not (token.endswith(".py") or token.endswith(".sh")):
                continue
            if "${" in token:
                # An unresolved placeholder we don't own (e.g. ${PYTHON} glued into the path) — the
                # harness expands it at run time; we can't resolve it, so don't flag a false miss.
                continue
            candidate = Path(token)
            # A relative hook path is relative to the harness directory itself (.gigacode/), the
            # same root the hash-manifest (SR-8) hashes hooks/skills under.
            hook_path = candidate if candidate.is_absolute() else harness / token
            if not hook_path.is_file():
                problems.append(f"hook referenced in settings.json not found: {raw}")

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
