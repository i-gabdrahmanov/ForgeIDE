#!/usr/bin/env bash
# ForgeIDE harness deploy wrapper (SDD FR-1.4/SR-7).
#
# Best-effort activation of a project's .gigacode/ harness: makes sure the expected
# directories exist and that hook/skill scripts are executable. Content integrity itself is
# the IDE's job (the hash-manifest, SDD SR-8) — this script only prepares the tree deploy
# copies into the IDE's own harness-cache.
set -euo pipefail

project="${1:?usage: deploy.sh <project>}"
harness="$project/.gigacode"

mkdir -p "$harness/hooks" "$harness/skills"
find "$harness/hooks" "$harness/skills" -type f \( -name '*.py' -o -name '*.sh' \) -exec chmod +x {} + 2>/dev/null || true

exit 0
