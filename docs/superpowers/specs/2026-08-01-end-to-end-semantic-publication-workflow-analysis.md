# End-to-End Semantic Publication Workflow

## Status

Analysis and design direction. This document describes the target workflow and
the changes required to make it operational. It does not authorize a site
migration or mutation of the real review workspace.

## Executive conclusion

The site should treat links as semantic occurrences during private preparation
and approval, and resolve them to public routes only while materializing a
release. Each approved page should therefore be a three-file bundle:

~~~text
published/ru.md
published/en.md
published/references.json
~~~

The Markdown files contain inert semantic destinations such as
[title](ref:ref-0007). The sidecar maps each occurrence ID to a stable target
identity and preserves the authored target, heading, and visible label. Public
output resolves stable target identities against the set of approved public
pages. A target that is not public becomes plain visible text in the public
projection; its semantic identity remains in the private approved bundle.

This enables a new target note to become public without rewriting or
reapproving every already-approved referrer. Existing referrers already contain
the stable semantic occurrence; only the target registry used for the next
release changes.

The current code already contains most of this data model and projection
mechanism. The missing work is the end-to-end preparation boundary: initializing
semantic mode for a new site, obtaining target and backlink information from
Obsidian, upgrading legacy pages through the normal Prepare workflow, and
keeping stable target identities current without requiring a whole-vault scan
on every page.

## User-facing workflow

### One-time site initialization

A fresh site should use an explicit initializer, proposed as:

~~~text
astro-export init-semantic-site \
  --vault <vault> \
  --review <review-root> \
  --json
~~~

The command should:

1. Refuse to overwrite an existing semantic workspace or non-empty approved
   review state without an explicit recovery/backup decision.
2. Create the private semantic catalog and activation marker.
3. Establish the empty semantic review workspace as a valid semantic-mode
   workspace.
4. Leave published/ empty; no page is approved merely because the catalog
   exists.

The initializer is not a migration command. It does not inspect or rewrite
legacy approved snapshots. Its purpose is to establish the mode and identity
registry required by the ordinary publishing workflow.

### Publishing a new or edited note

The ordinary Obsidian actions remain the user-facing workflow:

~~~text
publish: true
→ Prepare note for publication
→ review/approve translation
→ Mark current translation reviewed
→ build from approved review output
~~~

There is no separate semantic-link action. Prepare creates a semantic
candidate, translation preserves the semantic occurrence IDs, approval stores
the complete candidate triple, and build projects that triple into public
routes.

### Publishing a target after its referrers already exist

Suppose an approved page contains:

~~~markdown
Read [The Lean Startup](ref:ref-0007).
~~~

If the target is not yet approved for publication, public materialization emits:

~~~markdown
Read The Lean Startup.
~~~

The private approved page still contains [ref:ref-0007]. Once the target receives
an approved snapshot, the next release can emit the localized route without
changing the referrer’s approved Russian or English bytes.

This is the central late-binding behavior. It is different from deleting the
semantic link from the approved page when the target is currently private.

## Current code grounding

### Semantic page bundle and validation

[PageReferenceMap](../../../exporter-java/src/main/java/dev/eugene/astroexport/references/PageReferenceMap.java)
already models the intended sidecar. Its current reference payload contains:

~~~text
targetRef
authoredTarget
heading
label
~~~

The map also records the page identity, source path, RU and EN hashes, and the
ordered occurrence IDs. [PageReferenceMapCodec](../../../exporter-java/src/main/java/dev/eugene/astroexport/references/PageReferenceMapCodec.java)
validates that:

- RU and EN hashes match the stored bytes;
- both languages contain the same semantic occurrence IDs;
- occurrence order matches references.json;
- no occurrence is duplicated, missing, or unknown.

These are the right invariants for translation-safe semantic links.

[SemanticReferenceMarkdown](../../../exporter-java/src/main/java/dev/eugene/astroexport/references/SemanticReferenceMarkdown.java)
already parses [label](ref:ref-xxxx) outside protected Markdown contexts and
projects a semantic occurrence either to a public destination or to its plain
visible label.

### Preparation

[PrepareWorkflow](../../../exporter-java/src/main/java/dev/eugene/astroexport/prepare/PrepareWorkflow.java)
already branches on [SemanticSchemaState](../../../exporter-java/src/main/java/dev/eugene/astroexport/migration/SemanticSchemaState.java):

- legacy mode uses the existing entry resolver;
- semantic mode loads a VaultReferenceCatalog, builds a semantic reference
  plan, renders Russian semantic Markdown, and stages a candidate RU/EN/map
  triple.

The translation instructions already require the English candidate to preserve
every semantic occurrence ID exactly once and in Russian order. Candidate
installation binds the map to both language byte hashes.

The important gap is that semantic Prepare currently loads the catalog but does
not itself ensure that newly relevant target identities have entered it. A new
note can therefore be absent from the catalog even though it is now being
prepared for publication.

### Approval and release projection

[ReviewWorkspace](../../../exporter-java/src/main/java/dev/eugene/astroexport/review/ReviewWorkspace.java)
already chooses semantic published-snapshot storage when the semantic marker is
active. The semantic snapshot store expects RU, EN, and references.json as one
atomic unit.

[ApprovedTargetRegistry](../../../exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedTargetRegistry.java)
maps stable target references to approved public IDs and localized routes.
[ApprovedReleaseMaterializer](../../../exporter-java/src/main/java/dev/eugene/astroexport/release/ApprovedReleaseMaterializer.java)
uses that registry during public projection. If a target is absent from the
approved registry, the semantic destination is removed while the visible label
remains.

[ReferenceImpactIndex](../../../exporter-java/src/main/java/dev/eugene/astroexport/release/ReferenceImpactIndex.java)
already derives inbound references from approved page maps. This should remain a
derived index, not a second durable authority.

### Obsidian bridge

The current plugin in [obsidian-plugin/main.js](../../../obsidian-plugin/main.js)
already has direct access to this.app, the active TFile, and the vault base
path, but currently delegates Prepare to an external Java process through
[bridge-client.js](../../../obsidian-plugin/bridge-client.js). The bridge passes
the vault, note, review root, jobs root, and JSON mode; it does not currently
pass Obsidian’s in-memory metadata graph.

An Obsidian plugin can derive backlinks from the metadata cache’s resolved-link
map and inspect exact source-link occurrences through getFileCache. See the
official [MetadataCache API](https://docs.obsidian.md/Reference/TypeScript%20API/MetadataCache/unresolvedLinks)
and [getFileCache API](https://docs.obsidian.md/Reference/TypeScript%20API/MetadataCache/getFileCache).
The graph should be treated as a fast workflow input and acceleration
mechanism. Source Markdown and exporter-side validation remain authoritative.

## Refined semantic data model

### Occurrence identity

Every authored link occurrence in one page receives a stable local ID such as
[ref-0007]. IDs are preserved across translation and reused across editorial
revisions when the occurrence can be matched conservatively by target identity,
heading, label, order, and surrounding context.

If an occurrence is inserted, it receives a new ID. If a rename or edit makes
identity ambiguous, preparation blocks that page rather than silently moving an
ID to a different link.

### Target identity

targetRef is the durable semantic target identity, for example
[vault-ref-0012]. It is not a route and is not the target’s publicId.

The target registry may additionally know:

~~~text
targetRef → sourcePath, publicId, collection, RU route, EN route, state
~~~

Public IDs and routes are resolved late from the approved target snapshot. This
keeps route changes and target publication state out of the referrer’s
translation identity.

### Unpublished targets

If an authored target can be resolved to a vault note but has no approved public
snapshot, the semantic occurrence should still be retained privately. The
release projector strips only the public destination, not the occurrence
identity.

If the target cannot be resolved at all, preparation should record a diagnostic
and may keep the visible text without inventing a target identity. When the
target later becomes resolvable, the referring page needs a new candidate and
approval because its semantic occurrence set changes.

## Obsidian-assisted preparation

The plugin should provide the exporter with a bounded graph snapshot for the
current Prepare operation.

### Inputs from Obsidian

For the current note, the plugin can provide:

~~~json
{
  "notePath": "blog/example.md",
  "targets": [
    {
      "sourceOccurrence": 1,
      "targetPath": "books/lean-startup.md",
      "heading": "Introduction",
      "label": "Бережливый стартап"
    }
  ],
  "backlinks": [
    "blog/another-page.md"
  ]
}
~~~

The exact transport can be a temporary JSON sidecar or a JSON stdin/bridge
payload. The exporter must validate paths and source snapshots; it must not
trust plugin graph data as a replacement for reading the source note.

### Lazy catalog maintenance

The preferred new-site behavior is lazy target identity allocation:

1. Prepare resolves the current note’s authored targets through Obsidian.
2. The exporter reuses an existing targetRef or allocates one for a newly
   observed target.
3. The updated catalog is written atomically before the candidate is committed.
4. The candidate map records the target identity even if the target is not yet
   public.

This avoids a full-vault scan for every note while preserving late binding for
targets that are actually referenced. The plugin’s backlink list can identify
referrers that may need a future candidate refresh when a target becomes public.

The reverse backlink list is not used to rewrite approved pages automatically.
It is used to report or queue affected referrers. Approval remains the boundary
for changes to their semantic occurrence sets.

## Legacy-note upgrade path

The twenty currently published pages do not have semantic bundles. They should
be upgraded through a semantic variant of the normal Prepare workflow rather
than through hand-authored migration JSON.

For each legacy note:

1. Read the source note and resolve its authored links.
2. Render a semantic Russian candidate with occurrence IDs.
3. Use the existing approved English snapshot as translation context.
4. Convert or regenerate the English candidate while preserving visible wording
   where possible and binding every matched link to the corresponding occurrence
   ID.
5. Block when duplicate or ambiguous legacy links cannot be aligned safely.
6. Validate the RU/EN/map triple.
7. Let the user review and approve it through the ordinary workflow.

The old published snapshot must remain untouched until the new semantic candidate
is approved. This makes the operation recoverable and allows the twenty pages to
be upgraded one at a time.

The key distinction is:

- no manual semantic-link-decisions.json authoring should be required;
- editorial approval may still be required for genuinely ambiguous legacy
  English alignment.

## Required implementation direction

### 1. Add semantic-site initialization

Introduce an explicit CLI operation that creates a fresh semantic workspace. It
should use the existing semantic operation lock, validate an empty or explicitly
approved target root, write the catalog/activation state atomically, and return
a structured JSON result.

It must not pretend that an empty catalog means every page is approved.

### 2. Add a graph-aware bridge contract

Extend the plugin bridge contract so Prepare can carry current-note target
descriptors and backlinks. Keep the external exporter authoritative over source
bytes, target identity validation, and candidate output.

The plugin should not silently edit referrers. It may show or enqueue the pages
that need re-preparation.

### 3. Make target identity allocation lazy and atomic

Add a focused catalog operation that reconciles only the target descriptors
observed during the current Prepare. It must preserve stable IDs, reject unsafe
or ambiguous identity matches, and avoid catalog writes when there is no change.

The catalog update and candidate installation need a clear recovery contract. A
catalog identity allocated for a failed candidate may remain as an unused active
identity, or the operation may stage both and publish them together; the choice
must be explicit and tested. It must never cause an approved page to point at a
different target silently.

### 4. Add semantic legacy preparation

Prepare needs a well-defined behavior when semantic mode is active but the page
has only legacy approved RU/EN files. The behavior should generate a candidate
semantic triple without mutating the legacy published snapshot.

The conversion must be occurrence-specific. “The first N Markdown links” is not
acceptable because unrelated links, images, escaped links, duplicate labels,
and reordered English links can corrupt identity.

### 5. Preserve late-bound materialization

Keep public projection separate from private approved snapshots. The release
materializer should continue to:

- resolve targetRef through approved target snapshots;
- use localized routes and heading fragments only at projection time;
- strip unavailable destinations to visible labels;
- record diagnostics/impact evidence for ignored private targets;
- emit no ref: or vault-ref-* tokens publicly.

## End-to-end sequence

~~~mermaid
flowchart TD
  A[Obsidian note publish true] --> B[Prepare current note]
  B --> C[Obsidian metadata graph resolves targets and backlinks]
  C --> D[Exporter allocates or reuses targetRef values]
  D --> E[Russian semantic Markdown plus references.json plan]
  E --> F[Translation preserves ref IDs and order]
  F --> G[Validate RU EN occurrence order and hashes]
  G --> H[Approve translation]
  H --> I[Atomic published RU EN references triple]
  I --> J[Build approved release]
  J --> K{Target has approved public snapshot?}
  K -->|yes| L[Localized public route]
  K -->|no| M[Visible label only]
~~~

When the missing target later becomes approved, the next build takes the yes
branch without changing the referrer’s approved semantic bundle.

## Safety and invariants

The implementation should preserve these boundaries:

- approved snapshots are never mutated by inventory or Prepare;
- candidate and published triples are installed atomically;
- RU and EN semantic occurrence order is identical;
- every semantic occurrence has exactly one sidecar entry;
- stable target identities survive recognized renames;
- ambiguous identity changes block preparation;
- target public IDs/routes never become the referrer’s semantic identity;
- public output contains no private semantic destinations or catalog tokens;
- target publication does not silently rewrite or reapprove existing referrers;
- Obsidian graph data accelerates the workflow but does not replace exporter
  source/hash validation;
- concurrent Prepare, approval, and build operations remain lock-guarded.

## Suggested implementation sequence

1. Add tests for the desired semantic bundle and public projection behavior.
2. Add the fresh semantic-site initializer and mode-state tests.
3. Add lazy target catalog allocation with atomic persistence.
4. Extend the Obsidian bridge with target/backlink data and validate the
   protocol.
5. Add semantic legacy preparation for one page, including ambiguous-link
   blocking.
6. Add the twenty-page fixture/acceptance workflow without touching the real
   review workspace.
7. Verify new-target publication activates existing semantic referrers without
   changing their approved hashes.
8. Verify unresolved targets remain plain in public output but retain private
   semantic identity when resolvable.
9. Only then consider applying the workflow to the real site.

## Success criteria

The design is complete when a fresh site can be initialized once, a new note can
be prepared and approved through the existing Obsidian workflow, and its
semantic bundle is produced without a separate link-maintenance step.

It is also complete when each of the twenty legacy pages can pass through the
same Prepare/review boundary and receive a valid semantic bundle without
hand-authoring occurrence decisions or rewriting unrelated approved pages.

Finally, publishing a new target must change only the target’s approved triple
and the next public projection. Existing referring pages must retain their
approved RU/EN bytes and semantic map hashes.
