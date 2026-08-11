## MODIFIED Requirements

### Requirement: ADM-01 Discover explicitly selected notes

The exporter SHALL discover Markdown source notes whose parsed frontmatter value `publish` is Boolean `true`, including notes in normally ignored vault paths, and SHALL exclude absent, false, string-valued, or malformed publication flags. Discovery order is deterministic (sorted by vault-relative path), not incidental to filesystem or map traversal order — both the in-memory and real vault adapters honor this.

#### Scenario: Selected note is discovered
- **GIVEN** a vault-relative Markdown file with parsed frontmatter `publish: true`
- **WHEN** publication discovery scans the vault
- **THEN** the file is present exactly once in the selected-note set
- **AND** a selected note under a normally tool-ignored path (e.g. a dotfolder) is discovered the same as any other

#### Scenario: Lookalike publication flag is excluded
- **GIVEN** a Markdown file whose `publish` value is absent, false, or the string `"true"`
- **WHEN** publication discovery scans the vault
- **THEN** the file is absent from the selected-note set

#### Scenario: Discovery order is deterministic
- **GIVEN** multiple selected notes at different vault-relative paths
- **WHEN** publication discovery scans the vault twice with no vault changes between scans
- **THEN** both scans return the selected notes in the same sorted-by-path order

### Requirement: ADM-05 Validate the bounded request, not unrelated notes

Note-scoped commands SHALL validate the requested selected note and its direct safety dependencies without making unrelated invalid vault notes a blocker.

#### Scenario: Unrelated invalid note exists
- **GIVEN** the requested note passes admission and another selected vault note is invalid
- **WHEN** the operator prepares or inspects the requested note
- **THEN** the requested note's result is determined without being blocked by the unrelated note

#### Scenario: Whole-vault release is requested
- **GIVEN** one or more selected notes fail admission
- **WHEN** a whole-vault manifest or release is requested
- **THEN** the aggregate operation is blocked or omits no invalid selected note silently
- **AND** diagnostics identify every selected note that prevents a complete release
- **AND** the `write-publication-manifest` command is this requirement's read-only whole-vault manifest: it reports one entry per selected note (its identity when admitted, or its diagnostics when not), never dropping a failing entry to produce a manifest that looks complete
- **AND** the command reports the manifest as complete only when every selected note admits successfully; otherwise it reports the manifest as incomplete while still listing every selected note's outcome, admitted or not

---

**Exclusions and unresolved choices for this slice (not normative):**
- `write-publication-manifest` is read-only: it never writes workflow status, candidate, approved, or release state. Reconciling workflow status for admitted notes remains `refresh-publication-queue`'s job, unchanged by this slice.
- Actually releasing a whole vault (installing every admitted note's release output) is out of scope. This requirement only establishes the discovery/admission manifest a future whole-vault release slice would consume as its precondition.
- Every content kind beyond `blog/essay` is out of scope (S17a–f introduces the rest); the manifest lists only essay-kind outcomes for this exporter edition.
- The exact response shape (`PublicationManifest`/`ManifestEntry` field names, whether it reuses `BridgeResponse` or is its own standalone type) is a technical-design concern, resolved in `design.md`, not decided here — following the precedent `write-publication-contract` (S15) and `build-from-review`'s `ReleaseResult` (S06) already set for CLI-only result types with no natural fit in `BridgeResponse`'s single-identity shape.
