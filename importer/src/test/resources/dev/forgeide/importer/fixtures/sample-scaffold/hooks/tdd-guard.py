#!/usr/bin/env python3
"""Fixture PreToolUse hook (the real Forge tdd-guard blocks edits that skip RED).

Referenced from hooks/settings.hooks.json as "python3 hooks/tdd-guard.py" — the import must
carry this script into the target project's .gigacode/hooks/ or preflight fails (T38).
"""
import sys

if __name__ == "__main__":
    sys.exit(0)
