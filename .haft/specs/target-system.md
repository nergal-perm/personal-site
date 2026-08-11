<!-- DRAFT — onboarding by haft agent on 2026-08-02; operator must review and edit -->

# Target System Spec

## TS.publication-environment.001 Explicitly reviewed vault notes become bilingual public pages

```yaml spec-section
id: TS.publication-environment.001
spec: target-system
kind: target.environment
title: Explicitly reviewed vault notes become bilingual public pages
owner: human
statement_type: explanation
claim_layer: description
status: active
valid_until: "2027-08-02T00:00:00Z"
depends_on: []
supersedes: []
terms: [publish flag, approved translation baseline]
target_refs: [README.md]
evidence_required: []
```

An author-marked vault note becomes a public page in Russian and English only after the plugin, exporter review, and approved baseline path completes. Notes without that explicit approval remain private; later source edits leave the published page unchanged until re-review creates a new approved baseline.

## TS.environment-change.001 Approved vault notes become published bilingual blog pages

```yaml spec-section
id: TS.environment-change.001
spec: "A vault note marked publish: true with required frontmatter, once carried through obsidian-plugin -> exporter-java review -> an approved RU/EN baseline -> Astro build, becomes a live page on the personal blog site in both Russian and English. The site's set of published pages is fully determined by notes the author has explicitly reviewed and approved for publication, not by everything present in the private vault; later edits to a note do not change the published page until re-reviewed and re-approved."
kind: environment-change
title: Approved vault notes become published bilingual blog pages
owner: human
statement_type: explanation
claim_layer: description
status: active
valid_until: "2027-08-02T00:00:00Z"
depends_on: []
supersedes: [TS.placeholder.001]
terms: [publish flag, approved translation baseline, translation pair]
target_refs: [README.md, exporter-java/README.md]
evidence_required: []
```

This is the core observable-change claim for the whole pipeline: a private vault note becomes a public, bilingual, reviewed page. Approved by operator 2026-08-02.

## TS.environment-change.002 Approved English/Russian baseline is immutable except through explicit re-review

```yaml spec-section
id: TS.environment-change.002
spec: "Once astro-export mark-reviewed durably writes review/<collection>/<publicId>/published/{ru.md,en.md,references.json}, that RU/EN pair is the approved baseline: prepare, export, build-from-review, Astro build, preview, and deployment all read it but none of them can change it. A later edit to the Russian source note only affects what a subsequent prepare diffs against; the previously published pair on the live site stays byte-identical until the author runs mark-reviewed again."
kind: environment-change
title: Approved translation baseline changes only via mark-reviewed
owner: human
statement_type: explanation
claim_layer: description
status: active
valid_until: "2027-08-02T00:00:00Z"
depends_on: [TS.environment-change.001]
supersedes: []
terms: [approved translation baseline, candidate triple, mark-reviewed]
target_refs: [exporter-java/README.md]
evidence_required: []
```

Grounded in exporter-java/README.md "Approved translation baseline" section. Approved by operator 2026-08-02.

## TS.environment-change.003 A semantic link only resolves to a localized target once that target has an approved baseline

```yaml spec-section
id: TS.environment-change.003
spec: "A referrer note that links to another vault note materializes, on the published site, as a plain approved label while the target note has no approved (published/) baseline yet — even if the referrer itself is approved. Once the target later gets its own approved RU/EN pair, the SAME already-published referrer snapshot starts resolving that link to the target's localized page, without requiring the referrer to be re-reviewed, re-approved, or retranslated. Un-publishing the target (removing publish: true) only changes what release selection includes; it does not change referrer state either."
kind: environment-change
title: Semantic links activate on target approval, independent of referrer review
owner: human
statement_type: explanation
claim_layer: description
status: active
valid_until: "2027-08-02T00:00:00Z"
depends_on: [TS.environment-change.001]
supersedes: []
terms: [semantic link, references.json, target approval, release materialization]
target_refs: [exporter-java/README.md]
evidence_required: []
```

Grounded in exporter-java/README.md "Semantic migration and release commands" + link-activation paragraph, and the recent commits (`bind semantic drafts to verified occurrences`, `Harden semantic-link decision safety`). Approved by operator 2026-08-02, who flagged this as the most-wanted capability in the whole pipeline: by letting a referrer's approved snapshot pick up a target's link automatically on later approval, it removes the need to re-review or re-touch already-published notes just because something they link to gets published later — a significant reduction in the friction and number of manual actions needed to publish.
