# Agent task — {{step_id}}

<!-- TODO: describe what the model must do for this step. Reference expected inputs
     (artifacts from upstream steps, params) and the artifacts this step must produce
     under `expects`/`allowed_write`. -->

## Rules

- Never perform outward actions yourself (no `git push`, no PR creation, no Jira writes) —
  those only happen through a dedicated `outward` step the engine runs after a human gate.
- Only write inside the paths this step's `allowed_write` allows; anything else is a
  scope-diff incident.

## Result contract (mandatory — do not remove or restructure)

At the very end of your final message, emit exactly one fenced JSON block with this exact
shape. The engine parses this block to close the step; a missing or unparsable block fails
the step as `FAILED(stream)`. `status` is informational only — the judge(s) and the engine,
not your own self-assessment, decide whether the step actually passes.

```json
{
  "step_id": "{{step_id}}",
  "status": "done",
  "artifacts": [],
  "pending_questions": [],
  "summary": ""
}
```
