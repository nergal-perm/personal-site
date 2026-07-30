# Canonical Review Frontmatter Ordering

## Context

The Zed review workflow compares each approved Markdown snapshot with its
proposed replacement:

- `published/ru.md` with `ru.md`
- `published/en.md` with `en.md`

The final Astro writer already sorts frontmatter mappings recursively. The
review workspace and translation candidate paths do not share that behavior.
As a result, semantically identical frontmatter can appear changed when an
exporter or translation agent emits the same fields in a different order.

## Goal

Every exporter-generated review document must serialize frontmatter mappings
in one canonical order so that review diffs show content changes rather than
field-order changes.

## Non-goals

This change will not:

- reorder list items;
- change frontmatter values or note bodies;
- canonicalize source vault notes;
- change manifest or translation hash inputs;
- rewrite copied legacy Markdown overrides;
- reserialize manually reviewed English content during approval;
- change the approved-snapshot ownership boundary.

## Canonicalization Contract

Add a pure shared `FrontmatterCanonicalizer` in
`dev.eugene.astroexport.frontmatter`.

The canonicalizer:

1. Returns a fresh mapping rather than mutating its input.
2. Sorts every mapping by key with Java's natural `String` order, matching the
   current `SiteWriter` output.
3. Applies the same ordering recursively to nested mappings.
4. Canonicalizes mappings contained in lists.
5. Preserves list-item order.
6. Preserves scalar values and scalar types.

The utility owns only data ordering. YAML and JSON serializers continue to own
quoting, scalar formatting, indentation, and line endings.

## Integration Boundaries

### Review workspace

`ReviewWorkspace.serializeMarkdown` canonicalizes metadata immediately before
passing it to SnakeYAML.

This covers:

- generated Russian review files;
- generated English candidates when `translationStatus` is normalized;
- editorial JSON migrations that are rendered as Markdown.

Markdown overrides copied during legacy migration remain byte-for-byte copies
because they are not exporter-generated serialization.

### Translation candidate template

`PrepareWorkflow.candidateTemplate` canonicalizes its assembled metadata before
rendering the template passed to the translation agent.

The candidate returned by the agent is parsed and reserialized by
`ReviewWorkspace.setGeneratedReviewStatus`, so the installed proposed `en.md`
is canonical even if the agent changes key order.

### Final site writer

`SiteWriter` replaces its private recursive sorting implementation with the
shared canonicalizer for both Markdown frontmatter and editorial JSON.

Existing site output must remain byte-identical. This integration makes the
existing final-output ordering rule and the new review-output rule one shared
contract.

## Approval and Snapshot Semantics

`mark-reviewed` remains content-preserving:

- `setReviewedStatusPreservingContent` changes only the
  `translationStatus` scalar;
- the reviewed English bytes are not parsed and reserialized;
- the published English snapshot contains the exact reviewed bytes;
- the published Russian snapshot comes from the canonical exporter-generated
  rendering.

Therefore a user can intentionally reorder English frontmatter during review.
That manual change is preserved and can appear once in the next diff. The
exporter does not silently rewrite approved content.

## Data Flow

For Russian review content:

1. `ManifestBuilder` derives a `ManifestEntry`.
2. `ReviewWorkspace` assembles review-only fields such as `route` and
   `targetPath`.
3. The shared canonicalizer recursively orders the assembled metadata.
4. SnakeYAML renders `ru.md`.

For generated English review content:

1. `PrepareWorkflow` builds and canonicalizes the candidate template.
2. The translation agent writes a complete candidate.
3. Candidate validation parses the candidate.
4. `setGeneratedReviewStatus` reserializes the candidate through the shared
   canonicalizer.
5. The canonical bytes become proposed `en.md`.

For final site output:

1. Russian and English manifests retain their existing semantics.
2. `SiteWriter` canonicalizes metadata with the shared utility.
3. Existing YAML or JSON serializers write the managed site tree.

## Error Handling

The canonicalizer performs no I/O and introduces no recovery state. Existing
parsers and validators remain responsible for malformed YAML, invalid
frontmatter shapes, unsafe paths, and translation-contract violations.

Any serialization failure follows the existing caller behavior:

- review generation fails before replacing a durable review file;
- candidate validation fails before installing proposed English content;
- site staging fails before replacing managed site trees.

## Testing

Development follows a red-green-refactor cycle.

Focused tests will cover:

1. Top-level and nested mappings are sorted.
2. Mappings inside lists are sorted while list-item order is preserved.
3. Scalar values and types are unchanged.
4. Russian review Markdown uses canonical ordering, including review-only
   fields.
5. Agent-produced English frontmatter is canonical after generated-status
   normalization.
6. Candidate templates are canonical before translation.
7. Existing `SiteWriter` exact-output tests remain byte-identical.
8. Reviewed-status replacement and approved English snapshots remain
   byte-preserving apart from the explicit status change.

After focused tests pass, run the complete exporter test suite.

## Acceptance Criteria

- Fresh proposed `ru.md` and exporter-generated `en.md` files sort every
  frontmatter mapping recursively.
- Reordering map keys alone no longer creates Zed review diff noise between
  exporter-generated versions.
- List order remains unchanged.
- Manifest and translation hashes do not change because canonicalization is an
  output-format concern.
- Final Astro output is byte-identical to output from the current recursive
  `SiteWriter` sorter.
- `mark-reviewed` still snapshots the exact reviewed English bytes.
