## MODIFIED Requirements

### Requirement: PCM-05 Resolve assets safely and content-address them

The exporter SHALL resolve referenced publishable assets within the vault, prefer an exact vault-relative match over basename fallback, require basename fallback to be unique, and materialize each accepted asset under a deterministic content-derived name. Resolution runs on the asset-like embed targets (`![[Target]]` with a recognized publishable-asset extension) that PCM-03's link/transclusion resolution step identifies but does not itself resolve. An accepted asset's public reference is a content-addressed path under the vault-asset public route already reserved for this purpose in the site's managed-content contract (`public/assets/vault/`), built from the asset's SHA-256 content hash and a normalized extension (`.jpeg` folds to `.jpg`). The rewritten Markdown for every accepted asset — image, audio, or video alike — is a Markdown image/link reference (`![label](path)`) to that public asset; this requirement does not prescribe type-specific HTML rendering (e.g. `<audio>`/`<video>` tags) for any asset type.

#### Scenario: Exact asset path exists
- **GIVEN** an image, audio, or video reference with an exact safe vault-relative target
- **WHEN** assets are resolved
- **THEN** that target is selected even if another file has the same basename
- **AND** public Markdown refers to its content-addressed public asset as a Markdown image/link reference

#### Scenario: Basename is ambiguous or unsafe
- **GIVEN** no exact target and multiple basename matches, or a target that escapes through traversal or symlink
- **WHEN** assets are resolved
- **THEN** publication is blocked with an asset diagnostic
- **AND** no candidate is installed or replaced

#### Scenario: Identical bytes are referenced more than once
- **GIVEN** multiple accepted references to identical asset bytes
- **WHEN** assets are materialized
- **THEN** one deterministic public asset is emitted and all references use it

---

**Exclusions and unresolved choices for this slice (not normative):**
- Type-specific asset rendering (HTML5 `<audio controls>`/`<video controls>` tags, numeric-alias-as-width sizing) is out of scope. Every accepted asset — regardless of extension — resolves to a uniform Markdown image/link reference. This was an explicit design decision (favoring PCM-05's literal, spec-minimal text over exporter-java's richer legacy rendering) made during this slice's collaborative-design pass, not a baseline requirement change.
- This slice materializes an accepted asset into the candidate workspace only. Whether/how a materialized asset travels from candidate to approved snapshot and release output is unresolved here — approved-snapshot and release-materialization code paths are untouched by this slice (confirmed: neither `ReleaseOutputStore` nor `BuildFromReviewHandler` reference assets today).
- Asset variants, image optimization, remote (non-vault) assets, and media types beyond the existing recognized publishable-asset extension set are out of scope.
- The precise collaborator boundary (in-memory asset bytes first, a real vault/file adapter proven against the same contract second, per this project's outside-in slicing discipline) is a technical-design concern, not a functional one — resolved in `design.md`.
