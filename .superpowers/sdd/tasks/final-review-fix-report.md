# S02 final-review fix report

## Scope

This is the single consolidated fix wave requested by
`final-review-findings.md`. It starts from the clean `a98b857` worktree noted in
`progress.md`; no partial fix-wave changes were retained.

## Findings addressed

1. **ADM-02 Markdown regular-file gate**
   - Added `VaultRelativePath.hasMarkdownExtension()` as a pure, case-sensitive
     lexical query.
   - `InspectPublicationHandler` blocks a non-Markdown note before the vault
     port is read.
   - `FilesystemVaultReader` now admits only real paths that are regular files.
   - Added unit coverage for extension variants and a directory, plus handler
     and CLI coverage for a valid-frontmatter `.txt` note.

2. **Frontmatter scalar kinds**
   - Added package-private `FrontmatterScalar`, so the parser retains whether a
     token was quoted instead of reducing all scalars to `String`.
   - `flag` accepts only bare `true`; `string` excludes bare `null`, `~`, empty,
     `true`, and `false` while retaining quoted forms as strings.
   - Added parser tests for quoted `"true"`, bare `null`, bare `true`, and
     quoted `"null"`, plus admission coverage that blocks bare `sourceId: null`.

3. **Ambiguous frontmatter**
   - Duplicate keys and malformed key/value tokens now make the complete block
     unparseable, rather than choosing a last value.
   - Added a duplicate-key test that verifies all parsed values are absent.

4. **Read failure bridge contract**
   - `InspectPublicationHandler` converts `NoSuchElementException` and
     `UncheckedIOException` from the read/parse/admit sequence to the existing
     missing-note blocked response.
   - Added handler tests for both disappearing and unreadable notes.

5. **Shared success-response schema**
   - Defined `identity` and all four independent state fields in
     `bridge-contract/schema-v2.json` without making them top-level required
     fields, preserving blocked-response compatibility.
   - Added JS negative controls proving a non-string state and incomplete
     identity are rejected.
   - Strengthened the Java valid-essay conformance test with `ok: true` and
     `status: not_prepared` assertions.

6. **OpenSpec consistency**
   - Corrected the proposal to identify the two real deltas.
   - Corrected both scope-pin tooling notes: only those files carry no delta;
     archive the whole change normally, without `--skip-specs`.

7. **Design drift**
   - Updated the documented `Frontmatter.flag` return type and the complete
     `BridgeResponse.essayInspected` signature.

## Red/green evidence

- **RED:** the new Java path tests failed to compile because
  `hasMarkdownExtension()` did not exist. The new JS schema negative controls
  ran and failed: malformed success-state and identity values produced no
  validation errors under the previous schema.
- **GREEN (focused):**
  `mvn -f publication-exporter/pom.xml -Dtest=VaultRelativePathTest,FilesystemVaultReaderTest,FrontmatterTest,EssayAdmissionTest,InspectPublicationHandlerTest,InspectPublicationCliAcceptanceTest,SchemaConformanceTest test`
  passed: 68 tests, 0 failures/errors.
- **GREEN (full Java):** `mvn -f publication-exporter/pom.xml test` passed:
  84 tests, 0 failures/errors.
- **JS suite:**
  `node --test obsidian-plugin/tests/bridge-client.test.cjs obsidian-plugin/tests/schema-conformance.test.cjs`
  produced 52 passes and one known pre-existing failure:
  `community plugin enablement retains the live list and adds only this plugin`
  fails with `ENOENT` for the sibling-worktree path
  `/Users/eugene/Dev/personal-site/.worktrees/community-plugins.json`. This is
  the same unrelated failure recorded in `progress.md`; all 17 schema tests,
  including the new negative controls, passed.

## Design check

`FrontmatterScalar` owns scalar-kind interpretation and remains package-private;
`Frontmatter` owns block parsing; `VaultRelativePath` owns lexical path facts;
and the handler only maps port failures to the bridge result. This keeps the
public protocols narrow, constraints at their owning abstraction, and the
handler's preflight flow as guard clauses.

## Concerns

The review's deliberately scoped handling catches the specified normal read
failures but does not replace the port's check/read protocol with a
`SecureDirectoryStream` design. That larger TOCTOU redesign was explicitly out
of scope for this wave.

Strict `openspec validate s02-inspect-valid-essay --strict` still reports the
two intentional no-delta scope-pin files. Their tooling notes describe that
tool limitation; the OpenSpec status remains 44/44 complete and the actual
review-and-approval/workflow-bridge deltas are retained for normal archiving.
