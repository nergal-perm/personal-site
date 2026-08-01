# Agent Instructions

## Replies

Always start replies with `STARTER_CHARACTER` followed by a space (default:
`🍀`). Stack additional requested emojis; do not replace the starter character.

## Subagent-Driven Development Model Routing

Apply these rules whenever using
`superpowers:subagent-driven-development`.

### Preflight

- Run implementation only in the isolated worktree required by the skill.
  Never implement directly on `main` or `master` without explicit user
  authorization.
- Require the parent thread to use `gpt-5.3-codex-spark` with medium reasoning
  effort. If it does not, stop before dispatching an implementer and ask the
  user to start or select a Spark parent thread.
- Leave `agents.default_subagent_model` unset. Each role is routed below.

### Role Routing

- Fresh task implementer: omit the model override intentionally so the
  subagent inherits `gpt-5.3-codex-spark` from the parent. Use medium reasoning
  effort.
- Fix rounds 1-3: resume the original Spark implementer.
- Task reviewer: explicitly use `gpt-5.6-terra` with high reasoning effort.
- Scoped task re-reviewer: explicitly use `gpt-5.6-terra` with medium
  reasoning effort.
- Fix rounds 4-5: dispatch a fresh `gpt-5.6-terra` implementer with high
  reasoning effort, as the skill's required capability escalation.
- Final fix wave: dispatch one `gpt-5.6-terra` implementer with high reasoning
  effort for the complete findings list.
- Whole-branch final reviewer: explicitly use `gpt-5.6-sol` with xhigh
  reasoning effort.
- Final scoped re-reviewer: explicitly use `gpt-5.6-sol` with xhigh reasoning
  effort.

### Workflow Invariants

- Never dispatch multiple implementation subagents in parallel.
- Record each dispatched role, requested model, reasoning effort, and whether
  the model was explicit or inherited in the plan-owned SDD ledger.
- The omitted Spark model is a version-gated exception to the stock skill's
  explicit-model rule. It exists because Codex CLI 0.146.0 accepts Spark as a
  parent model but rejects it as an explicit `spawn_agent` model.
- Once the active Codex release accepts explicit Spark subagent dispatch,
  remove this exception and specify `gpt-5.3-codex-spark` explicitly for fresh
  task implementers, restoring the stock skill rule.
