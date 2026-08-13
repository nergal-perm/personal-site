---
id: prob-20260813-s17f-slice-implementation-session-2026-08-13-gov-d48f3e37
kind: ProblemCard
version: 1
status: active
title: S17f — editorial/curated_page kind and the G7 grammar-freeze gate (ADM-03, ADM-04, PCM-02, PCM-06)
context: Governed by the greenfield exporter slice plan (openspec/implementation-plan.md), sub-problem of prob-20260803-fe9b3011. Follows S17e (music/album, prob-20260812-dfdb5d68, archived as 2026-08-13-s17e-music-album-kind), the last of the six slices in the S17a-S17f content-kind ladder. Unlike S17a-e, editorial/curated_page's structured body is not one uniform field set: legacy exporter-java/src/main/java/dev/eugene/astroexport/editorial/EditorialParser.java implements nine distinct page-type grammars (home, essays, claims, notes, music, library, concepts, now, about) keyed by frontmatter `editorialPage`, each with its own required Markdown sections/inline fields (H2/H3 headings, `Field::` inline syntax, wikilinks, bullet lists). The live site already consumes this kind's output as generated static JSON at site/src/data/pages/{ru,en}/*.json (e.g. about.json), rendered by dedicated views (site/src/views/AboutPage.astro, NowPage.astro) and routed via site/src/pages/{ru,en}/about.astro etc. — not an Astro content collection like blog/bibliography/music/concepts. The plan's own G7 gate ("Freeze current editorial grammar as one edition or version it independently") is explicitly required before this slice and is currently undecided. publication-admission/spec.md already contains placeholder scenario text mentioning "editorial page" from earlier kind-ladder edits (ADM-03/ADM-04 enumeration scenarios), but no editorial-specific scenario, PublicationKind, or release projection exists yet.
mode: standard
created_at: 2026-08-13T03:28:03Z
updated_at: 2026-08-13T03:28:03Z
---

# S17f — editorial/curated_page kind and the G7 grammar-freeze gate (ADM-03, ADM-04, PCM-02, PCM-06)

## Signal

PublicationKinds.installed() does not register editorial/curated_page; the exporter cannot admit, prepare, approve, or release any curated editorial page, so legacy exporter-java's EditorialParser remains the only path currently able to produce site/src/data/pages/*.json.

## Problem Profile

deep

## P2W Readiness

p2w_blocked

## Why Now

Last slice in the S17 content-kind ladder; completing it closes Milestone C (S12-S17f: current content breadth) per implementation-plan.md.

## Scope

One curated_page fixture (specific page type to be selected during collaborative design — the simplest legacy grammar is the leading candidate) proven end-to-end through prepare -> approve -> release, producing its own JSON-page release artifact via a kind-owned projection (release rule: kinds own their projection to managed content). The G7 decision must be recorded explicitly as a Haft decision, not decided incidentally in code. Full parity across all nine legacy page-type grammars is explicitly out of scope for this slice unless design work finds it trivially free; the plan does not schedule further content-kind slices after S17f, so any deferred page-type grammars become a follow-on problem, not silent scope creep here.

## Constraints

- Minimal-slice discipline per implementation-plan.md: 1-3 new OpenSpec scenarios, at most one new production boundary adapter, in-memory acceptance subset under 1 second, no foundation-only/refactor-only milestone.
- Must not decide G7 quietly through incidental code — record it as an explicit Haft decision before or during design.
- Must reuse the existing PublicationKind/PublicationKinds/AdmittedPublication seam from S17a; no new generic schema framework or speculative cross-kind abstraction.
- Release artifact shape for curated pages may differ from ordinary Markdown release (JSON page data) only if this slice's fixture actually needs it — do not redesign essay/note/claim/book/concept/album release paths.
- Whole prior acceptance suite (763 tests as of baseline) must stay green throughout.

## Acceptance

A recorded G7 decision exists (grammar frozen as one edition or versioned independently); an OpenSpec change (proposal, spec, design, tasks) documents the slice; one curated-page fixture completes prepare -> approve -> release through the CLI, producing a valid JSON page release artifact; write-publication-contract includes editorial/curated_page's kind-specific rules; the whole prior acceptance suite stays green; the final whole-branch review (Codex, GPT 5.6 Sol, max effort) passes with no unresolved Important/Critical findings.

## Blast Radius

One new PublicationKind implementation, one new release-projection adapter for JSON page artifacts, and additions to the shared PublicationKinds composition list. No changes to existing essay/note/claim/book/concept/album code paths expected.

## Reversibility

high — new code additive to the existing kind registry; revertible via git without touching prior kinds' behavior

## Spec Fit (Advisory)

State: spec_gap

Next expected action: draft_section

| Variant | State | SpecSections | Next action |
|---------|-------|--------------|-------------|
| probe | spec_gap | - | draft_section |


## Related History

- [decision] **PublicationKind/PublicationKinds seam extracted from EssayAdmission; NotePublicationKind added; blog/note proven through admit→prepare→approve→release** `dec-20260811-6c6724df`
- [problem] **Wire up the real semantic link resolver end-to-end** `prob-20260802-1803dd18`

<!-- haft:structured_data
{
  "acceptance": "A recorded G7 decision exists (grammar frozen as one edition or versioned independently); an OpenSpec change (proposal, spec, design, tasks) documents the slice; one curated-page fixture completes prepare -\u003e approve -\u003e release through the CLI, producing a valid JSON page release artifact; write-publication-contract includes editorial/curated_page's kind-specific rules; the whole prior acceptance suite stays green; the final whole-branch review (Codex, GPT 5.6 Sol, max effort) passes with no unresolved Important/Critical findings.",
  "blast_radius": "One new PublicationKind implementation, one new release-projection adapter for JSON page artifacts, and additions to the shared PublicationKinds composition list. No changes to existing essay/note/claim/book/concept/album code paths expected.",
  "constraints": [
    "Minimal-slice discipline per implementation-plan.md: 1-3 new OpenSpec scenarios, at most one new production boundary adapter, in-memory acceptance subset under 1 second, no foundation-only/refactor-only milestone.",
    "Must not decide G7 quietly through incidental code — record it as an explicit Haft decision before or during design.",
    "Must reuse the existing PublicationKind/PublicationKinds/AdmittedPublication seam from S17a; no new generic schema framework or speculative cross-kind abstraction.",
    "Release artifact shape for curated pages may differ from ordinary Markdown release (JSON page data) only if this slice's fixture actually needs it — do not redesign essay/note/claim/book/concept/album release paths.",
    "Whole prior acceptance suite (763 tests as of baseline) must stay green throughout."
  ],
  "profile": {
    "blockers": [
      "acceptance_probe missing",
      "freshness_disposition missing",
      "wish/ticket/chosen_method source requires explicit boundary before P2W readiness"
    ],
    "boundary_status": "partial",
    "level": "deep",
    "readiness": "p2w_blocked",
    "scope": "One curated_page fixture (specific page type to be selected during collaborative design — the simplest legacy grammar is the leading candidate) proven end-to-end through prepare -\u003e approve -\u003e release, producing its own JSON-page release artifact via a kind-owned projection (release rule: kinds own their projection to managed content). The G7 decision must be recorded explicitly as a Haft decision, not decided incidentally in code. Full parity across all nine legacy page-type grammars is explicitly out of scope for this slice unless design work finds it trivially free; the plan does not schedule further content-kind slices after S17f, so any deferred page-type grammars become a follow-on problem, not silent scope creep here.",
    "source_kind": "ticket",
    "why_now": "Last slice in the S17 content-kind ladder; completing it closes Milestone C (S12-S17f: current content breadth) per implementation-plan.md."
  },
  "reversibility": "high — new code additive to the existing kind registry; revertible via git without touching prior kinds' behavior",
  "semantic": {
    "carrier_binding": {
      "carrier_kind": "markdown",
      "carrier_ref": "prob-20260813-s17f-slice-implementation-session-2026-08-13-gov-d48f3e37",
      "source_of_truth": "sqlite",
      "storage_kind": "ProblemCard"
    },
    "profile": {
      "hash": "sha256:25225273a1f77d5c356fa7eb68d9a6e3748f8d40acb747a3a0188314b5329abf",
      "id": "haft-semantic-spine-v3.problem-card.v1",
      "source_kind": "embedded-profile",
      "source_ref": "embedded:haft-semantic-spine-v3/problem-card-v1",
      "valid_until": "2026-09-18T00:00:00Z"
    },
    "publication_projection": {
      "hash": "sha256:519acb6afd4f5be0480f38397df4e4f9d0997ee3c016d0e4c4def3c9d5cac800",
      "projection_kind": "problem_card_markdown",
      "sync_policy": "explicit_sync_validated_import",
      "views": [
        "working",
        "exact",
        "audit"
      ]
    },
    "publication_unit": {
      "carrier_hash": "sha256:53190f340ec959df65b9f917dbdebee18be0fcb7d5406f8504ac470ae1f18500",
      "publication_hash": "sha256:519acb6afd4f5be0480f38397df4e4f9d0997ee3c016d0e4c4def3c9d5cac800",
      "recoverability": {
        "mechanism": [
          "sqlite structured_data",
          "markdown structured_data block"
        ],
        "status": "exact"
      },
      "schema_version": 1,
      "source_edition_pin": {
        "hash": "sha256:53d2572a06f04e2bba2b34d037b15a6b1aac3896c15f2fabe190c333f7d8fa35",
        "ref": "episteme://haft/problem-card/prob-20260813-s17f-slice-implementation-session-2026-08-13-gov-d48f3e37/v1",
        "status": "pinned_by_source_hash"
      }
    },
    "reference_scheme": {
      "anchors": [
        "frontmatter.id",
        "structured_data.semantic.semantic_edition.id"
      ],
      "primary": "artifact_id"
    },
    "schema_version": 1,
    "semantic_edition": {
      "created_at": "2026-08-13T03:28:03Z",
      "family": "ProblemCard",
      "hash": "sha256:53d2572a06f04e2bba2b34d037b15a6b1aac3896c15f2fabe190c333f7d8fa35",
      "id": "episteme://haft/problem-card/prob-20260813-s17f-slice-implementation-session-2026-08-13-gov-d48f3e37/v1",
      "version": 1
    },
    "status": "exact"
  },
  "signal": "PublicationKinds.installed() does not register editorial/curated_page; the exporter cannot admit, prepare, approve, or release any curated editorial page, so legacy exporter-java's EditorialParser remains the only path currently able to produce site/src/data/pages/*.json.",
  "spec_fit": {
    "authority": "read_only_spec_fit_probe",
    "authority_boundary": "spec_fit_probe_is_advisory_not_approval_not_baseline_not_evidence_not_gate_decision_not_claim_truth_not_publication",
    "next_expected_action": "draft_section",
    "record_kind": "spec_fit_probe",
    "schema_version": 1,
    "state": "spec_gap",
    "variant_spec_fit": [
      {
        "expected_action": "draft_section",
        "proposed_delta": "no active SpecSection matched the decision draft with high confidence",
        "state": "spec_gap",
        "variant_ref": "probe"
      }
    ]
  }
}
haft:end -->
