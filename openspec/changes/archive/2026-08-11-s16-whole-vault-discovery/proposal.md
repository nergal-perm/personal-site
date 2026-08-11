## Why

`VaultReader.listPublishCandidates()` (`publication-exporter/src/main/java/dev/eugene/publicationexporter/vault/VaultReader.java`) already discovers every Markdown note whose parsed frontmatter has `publish: true`, and `NoteIntake.admit(...)` (`publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`) already validates one such note end-to-end (path safety, existence, parsing, `EssayAdmission`). `RefreshPublicationQueueHandler.refresh(...)` already iterates `listPublishCandidates()` per note — but it treats an inadmissible note as `EXCLUDED` and moves on silently, which is the correct behaviour for *that* command (queue reconciliation only concerns admitted notes) but is exactly the behaviour ADM-05's "Whole-vault release is requested" scenario forbids for a whole-vault manifest: an aggregate result must never silently omit an invalid selected note. No command today reports that guarantee. Nothing today also proves two contracts a trustworthy whole-vault listing needs: a deterministic ordering (`Files.walk`'s traversal order is filesystem-dependent, not sorted) and an ignored-path contract (a selected note under a normally tool-ignored path, e.g. a dotfolder, is still discovered) — `NullVaultReader.listPublishCandidates()` doesn't even guarantee order today either, since it funnels a `LinkedHashMap` through `Map.copyOf(...)`, whose iteration order is unspecified by the JDK.

This is `openspec/implementation-plan.md`'s S16 slice, governed by Haft problem `prob-20260811-d1f6e02e` under the slice-sequence decision `dec-20260803-76166a5e`. It is Milestone C's whole-vault-discovery slice, coming after S15's `dec-20260811-ad8fc743` (machine-readable contract) and before the S17a–f content-kind ladder — S16 stays essay-only, per the plan's explicit exclusion of any new content kind.

## What Changes

- Add a new read-only CLI command that lists every Boolean-selected note in the vault exactly once, in a deterministic order, excluding publish-flag lookalikes (absent/false/string `"true"`) and including selected notes under normally-ignored vault paths.
- If every selected note admits successfully, the command reports a complete manifest (one entry per admitted note's identity). If any selected note fails admission, the command does not silently produce a manifest that omits it — the result surfaces every failing note's diagnostics (ADM-05's aggregate-blocking guarantee), rather than quietly excluding it the way `refresh-publication-queue` does today.
- Give `VaultReader.listPublishCandidates()` a deterministic ordering contract (both the in-memory fake and the real filesystem adapter) and a tested ignored-path contract for the real adapter — hardening the existing method rather than introducing a new one, per the plan's "at most one new production boundary adapter" discipline (this slice adds zero *new* adapters; it strengthens the one that already exists).
- Reuse `NoteIntake.admit(...)` for per-note validation inside the new aggregate handler — no new admission logic, no duplicated path-safety/parsing/`EssayAdmission` code.

**Explicitly excluded from this slice** (per the S16 boundary in the implementation plan): mutating workflow/queue state (that remains `refresh-publication-queue`'s job, untouched here) and releasing a partially valid vault (aggregate release itself, and what a complete whole-vault release even means operationally, is out of scope — this slice only proves the discovery/admission manifest a future release slice would consume). Every content kind beyond `blog/essay` stays out of scope (S17a–f).

## Capabilities

### New Capabilities

None — this slice realizes requirements (ADM-01, ADM-05's aggregate path) already fully specified in the baseline; it does not introduce a new capability area.

### Modified Capabilities

- `publication-admission`: ADM-01 gains implementation (discovery already exists in code but has no command surfacing it, no ordering contract, and no dedicated ignored-path proof). ADM-05 gains its aggregate-path scenario's implementation ("Whole-vault release is requested") — its note-scoped scenario ("Unrelated invalid note exists") is already satisfied by every existing note-scoped command and is unchanged by this slice. Whether either requirement's scenario text needs sharpening for this slice's exact command shape is a question for the functional collaborative-design pass, not decided here.

## Impact

- **Modified:** `publication-exporter/` — a new CLI command plus its supporting application-layer handler (name and shape settled in `design.md`), reusing `NoteIntake`/`EssayAdmission` without changing their behaviour; `VaultReader.listPublishCandidates()`'s two implementations (`NullVaultReader`, `FilesystemVaultReader`) gain a deterministic-order contract; `FilesystemVaultReader` gains an ignored-path test proving dotfolder-nested selected notes are discovered.
- **Untouched:** `exporter-java/` (read-only compatibility oracle), `obsidian-plugin/`, `bridge-contract/schema-v2.json`'s structural shape, `refresh-publication-queue`'s own behaviour (its silent-exclusion semantics are correct for its own concern and are not the subject of this slice), `site/`, and every content kind beyond essay.
- **Governance:** implements Haft problem `prob-20260811-d1f6e02e`, under decision `dec-20260803-76166a5e` (slice sequence).
