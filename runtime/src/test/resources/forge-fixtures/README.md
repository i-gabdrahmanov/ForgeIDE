# forge-fixtures

Frozen snapshot of the real Forge hooks (NFR-6 contract test), vendored so the T15 contract test
is reproducible on any machine/CI without a checkout of the `forge` skills repo alongside this
one.

Source: `forge` repo, commit `a1d1e51814a163338729fd054c2be4c0bc502eb4`, path `hooks/`.

Vendored files: `tdd-guard.py`, `risk_ladder.py`, `_project.py` — the minimal set `tdd-guard.py`
needs to resolve the active step from a `ground/statements/<skill>/<feature>/manifest.json`
projection (`risk_ladder.manifest_status`/`active_manifest`). `risk-policy.json` and
`pipeline_phases.py` are intentionally not vendored: this contract only exercises the
`lite-red`/`lite-green` flat-manifest path, which needs neither (both have safe fallbacks when
absent — see `risk_ladder.load_policy`/`tdd-guard`'s `pipeline_phases` best-effort import).

If `tdd-guard.py`'s manifest-reading contract changes upstream, re-copy these three files from
the current `forge` repo's `hooks/` directory and update the commit hash above.
