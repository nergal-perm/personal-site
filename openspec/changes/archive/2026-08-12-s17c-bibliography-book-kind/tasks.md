<!--
Global constraints for every task:
- Module: publication-exporter (Maven, Java 17). Fresh verification command for completion: `mvn -f publication-exporter/pom.xml test`.
- Outside-in TDD: every production change starts from a failing test or acceptance assertion that proves the new book behavior or the selectedQuote block.
- No generic schema/reflection framework, no new collection/content-type switches in generic orchestration, and no changes to exporter-java/.
- Follow the current Null* / createNull() testing style. If an interface or constructor used by a fake changes, update the fake in the same task.
- Keep the book kind as one focused abstraction: final class, composition over inheritance, no type introspection, and intention-revealing method names.
- Do not silently pass through selectedQuote. This slice either projects supported book metadata correctly or blocks the note before candidate installation.
-->

## Task 1 — Admission and contract primitives

- [x] 1.1 Add `MarkdownNote.listOfScalars(String key)` and parser-focused tests covering a valid author list, an empty list, a non-list value, mixed scalar/non-scalar items, and blank author entries.
- [x] 1.2 Extend `FieldContract` with a required string-list field type and update the publication-contract serialization path plus `PublicationContractConformanceTest` so contract/runtime agreement can be checked for `authors`.
- [x] 1.3 Add `BookPublicationKind` with `/library/` route ownership, required `authors` validation, blocked `selectedQuote`, and deterministic contract output, then register it in `PublicationKinds.installed()` and update note-intake/inspect tests that currently expect `bibliography/book` to be unsupported.

## Task 2 — Book projection through the existing pipeline

- [x] 2.1 Implement the book metadata split inside `BookPublicationKind`: translated `PublicField`s for `title`, `description`, optional `use`, and optional `boundary`; invariant `structuredData` for `authors`, `publication`, `publicationDate`, `start`, `end`, and `readingStatus`, emitted in deterministic YAML order.
- [x] 2.2 Add or update focused tests around `PrepareHandler`, `MarkReviewedHandler`, and related fixtures so `bibliography/book` reuses the current translated-field pipeline, and invariant book metadata changes force review or stale translation through `structuredData` hashing instead of silent candidate reuse.
- [x] 2.3 Extend managed-site/frontmatter assertions to prove `bibliography/book` installs into `src/content/bibliography/{locale}/{publicId}.md` with translated `use`/`boundary`, invariant author/publication metadata, and no leaked selectedQuote output.

## Task 3 — End-to-end slice proof

- [x] 3.1 Add one full `bibliography/book` acceptance test covering admit → prepare → approve → build-from-review → install-to-site, with a source fixture that proves at least one author, one invariant book metadata field, and one translated book field.
- [x] 3.2 Update `write-publication-contract` CLI-level coverage so the emitted contract includes the new `bibliography/book` kind, its required `authors` field shape, and deterministic ordering alongside existing kinds.
- [x] 3.3 Run the focused suites touched by this slice first, then run `mvn -f publication-exporter/pom.xml test` and keep the OpenSpec tasks/state aligned with the verified outcome.
