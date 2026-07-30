# Task 2 Report: Stable Vault Catalog and Conservative Resolution

## Implemented
- Reworked `VaultNoteDescriptor` with non-following symlink vault scan and frontmatter extraction of `id`, `title`, `aliases`, and filename stem with diagnostics for invalid UTF-8/unsafe paths/duplicate IDs.
- Implemented `VaultReferenceCatalog` schema-v1 persistence at `.semantic-links/catalog-v1.json` with:
  - `load(Path)`
  - `read(byte[])`/`write()`
  - `writeAtomically(Path)` using temp sibling + atomic exchange
  - `reconcile(Path, List<VaultNoteDescriptor>)`
  - tombstone preservation for removed active entries.
- Implemented `VaultReferenceResolver` with precedence layers:
  1. exact vault path (without `.md`)
  2. stable ID
  3. timestamp-stripped stem
  4. title
  5. alias.
- Implemented `SemanticReferencePlanner.prepare(...)` with:
  - wiki-link tokenization outside protected contexts
  - rewriting to `[label](ref:XXXX)` for resolved links
  - unresolved handling with non-blocking `unresolved-reference` diagnostic
  - ambiguous target exception `ambiguous-reference-target`
  - conservative identifier reuse and blocking `reference-reconciliation-required`
  - new identifier allocation as lowest `ref-%04d` above previous max.

## Added/updated tests
- `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultReferenceCatalogTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/references/VaultReferenceResolverTest.java`
- `exporter-java/src/test/java/dev/eugene/astroexport/references/SemanticReferencePlannerTest.java`

## TDD / validation evidence
- `mvn -q -Dtest=VaultReferenceCatalogTest,VaultReferenceResolverTest,SemanticReferencePlannerTest,LinkProcessorTest test`
- Result: all targeted tests passed.

## Self-review findings
- `VaultReferenceCatalog.CatalogEntry.title` is retained in-memory but currently not serialized in `write()`; only `currentPath`, `stableNoteId`, `aliases`, `previousPaths`, `state` are persisted in catalog JSON.
- Resolver and planner logic follows task requirements, but planner ambiguity diagnostics depend on signature/signature-index matching and conservative order heuristics.
