---
id: note-20260818-prob-20260818-40bccb11-slice-s21-read-only-legac-a548cfbe
kind: Note
version: 1
status: active
title: G6 Cutover gate: operator selected in-place migration
context: Gate G6 from openspec/implementation-plan.md, needed before S21 per the plan's gate table.
mode: note
valid_until: 2026-11-16T11:35:06Z
created_at: 2026-08-18T11:35:06Z
updated_at: 2026-08-18T11:35:06Z
---

# G6 Cutover gate: operator selected in-place migration

## Observations

- Operator answer captured via AskUserQuestion during S21 problem framing
- haft_decision(action=decide) rejected with operator_confirmation_required — MCP is cli-only for binding governance acts

## Rationale

Operator explicitly chose in-place migration of legacy exporter-java content pairs over clean cutover, via interactive question during S21 kickoff. Preserves existing published history; keeps S22/S23 in scope as planned rather than removing them per the plan's clean-cutover rule. Formal binding via haft_decision requires manual CLI (cli-only mode); this note records the operator's answer so it isn't lost, but a proper DecisionRecord should be bound via CLI when convenient.
