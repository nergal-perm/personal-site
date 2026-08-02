# Term Map

```yaml term-map
entries:
  - term: publish flag
    domain: authoring
    definition: "The `publish: true` frontmatter field an author sets on a vault note to mark it as a candidate for the site. Necessary but not sufficient — a note must also pass exporter-java review before it appears live."
  - term: approved translation baseline
    domain: review-workflow
    definition: "The durable RU/EN/reference triple at review/<collection>/<publicId>/published/{ru.md,en.md,references.json}, written only by `astro-export mark-reviewed`. This is the only thing release builds ever read; nothing else in the pipeline can move it forward."
  - term: candidate triple
    domain: review-workflow
    definition: "The pending RU/EN/reference triple at review/<collection>/<publicId>/candidate/{ru.md,en.md,references.json}, written by `astro-export prepare`. Superseded by the approved baseline only when mark-reviewed succeeds; ignored by every release/build command until then."
  - term: review plan
    domain: review-workflow
    definition: "The ordered ru/en target plan returned by `inspect-publication --json` (bridge schema v3), reporting candidateState, approvedSnapshotState, semanticReferencesState, and releaseState so the plugin knows whether to open a first-review or an approved-vs-proposed diff."
  - term: semantic link / references.json
    domain: semantic-links
    definition: "The schema-v1 record binding a stable private pageRef to a vault sourcePath plus exact ruSha256/enSha256 hashes and per-occurrence target records, used to resolve cross-note links at release time instead of authoring raw URLs."
  - term: collection
    domain: content-model
    definition: "One of the four Astro content types (blog, bibliography, music, concepts) under src/content/<collection>/{ru,en}/<slug>.md. Collection name is internal storage, not the public URL section (see site/HANDOFF.md nav mapping, e.g. blog+contentType=essay -> /essays/)."
  - term: content/provenance gate
    domain: build-pipeline
    definition: "The pre-build check (scripts/check-content.mjs, wired into `npm run build`) that fails the Astro build unless ru/en id parity, resolvable links, and the last recorded release-provenance payload all hold."
status: active
```

Approved by operator 2026-08-02, no corrections needed.
