## 1. Decision carriers and strict JSON boundary

- [x] 1.1 Add immutable S22 decision-carrier values and a deterministic non-executable draft writer.
- [x] 1.2 Add strict JSON parsing that rejects duplicate, missing, unknown, wrong-shape, malformed, and draft-marked executable inputs.
- [x] 1.3 Prove the JSON/draft contract with focused legacy tests.

## 2. Fresh-inventory validation

- [x] 2.1 Add a validator that re-inspects S21 inventory before accepting a separate human decision carrier.
- [x] 2.2 Prove current decision acceptance and stale/draft rejection using existing in-memory workspaces.

## 3. Read-only command workflow

- [x] 3.1 Extend `legacy-inventory` with mutually exclusive draft-generation and decision-validation modes.
- [x] 3.2 Prove draft placement, strict validation, stale rejection, and review-workspace byte preservation through CLI acceptance tests.
- [x] 3.3 Run focused legacy/CLI tests and the complete `publication-exporter` Maven suite.
