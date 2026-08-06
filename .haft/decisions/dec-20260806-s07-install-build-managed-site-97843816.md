---
id: dec-20260806-s07-install-build-managed-site-97843816
kind: DecisionRecord
version: 1
status: active
title: install-to-site: ManagedSiteInstaller port + FilesystemManagedSiteInstaller adapter reading ApprovedSnapshotWorkspace directly
mode: standard
created_at: 2026-08-06T14:15:03Z
updated_at: 2026-08-06T14:15:03Z
links:
  - ref: prob-20260806-e95236b1
    type: based_on
---

# install-to-site: ManagedSiteInstaller port + FilesystemManagedSiteInstaller adapter reading ApprovedSnapshotWorkspace directly

## 1. Problem Frame

**Signal:** S06 (dec-20260806-s06-materialize-approved-essay-7671c117) materializes one approved essay into a fresh, review-root-scoped release output (ru.md, en.md, release-provenance.json) but nothing installs that generation into the site's actual managed content roots or proves an Astro build succeeds from it. The site already carries a pre-existing, exporter-independent contract the install must satisfy: site/scripts/check-content.mjs verifies a `.astro-export/release-provenance.json` manifest (schemaVersion, selectedPages, managedTrees, managedFiles, activationCount, deactivationCount, payloadDigest) over three managed trees (public/assets/vault, src/content, src/data/pages), plus a required-page-contract check (about, concepts, essays, home, library, music, notes, search, claims must exist in both locales) that S06's output does not produce. Milestone A (S01-S07) is incomplete without this — no plain essay has ever reached a real Astro build.

**Constraints:**
- At most 3 new OpenSpec scenarios targeted (REL-04, REL-05, REL-06) unless a written reason justifies more
- At most one new production boundary adapter (the managed-site-tree installer)
- In-memory acceptance subset stays under 1 second; exactly one slow Astro smoke test is allowed
- No replacing-an-existing-generation logic (REL-05's replace/recover scenarios are S10)
- No interrupted-replacement recovery (also S10)
- No new content kinds, assets, links, or multi-publication handling in this slice
- The required-page-contract (about/home/essays/etc.) is a pre-existing site-level contract this slice does not own generating; how the acceptance/smoke fixture satisfies it is a technical-design decision, not an assumption
- publication-exporter/pom.xml changes only if a real dependency is genuinely needed

**Acceptance:** A `build-from-review`-produced generation (S06) is installed into previously-absent managed site roots (public/assets/vault, src/content, src/data/pages) via a new production adapter; the install passes the existing site/scripts/check-content.mjs gate including its release-provenance verification; `astro build` completes against the installed output; no code-owned site file (templates, config, non-managed paths) changes. Fast acceptance runs against an in-memory managed-tree adapter (<1s); a real filesystem adapter contract and exactly one slow Astro smoke test (subprocess check-content.mjs + astro build against a real temp site fixture) verify integration end to end.

## 2. Decision

**Selected:** install-to-site: ManagedSiteInstaller port + FilesystemManagedSiteInstaller adapter reading ApprovedSnapshotWorkspace directly

**Selection policy:** Follow the proven S05/S06 shape (in-memory fake first, real create-only filesystem adapter proven against the same contract) unless the technical collaborative-design pass surfaces concrete evidence to deviate; read ApprovedSnapshotWorkspace directly rather than chaining through S06's ReleaseOutputStore output, since REL-01's "approved snapshots only" authority applies independently to each realization rather than implying a pipeline.

**Why selected:** Materializes the approved snapshot into the site's real managed content roots, following the exact proven staging/confinement shape S05/S06 established (StagedDirectoryInstall, create-only atomic moves, confinement checks). Reading ApprovedSnapshotWorkspace directly — the same source S06's build-from-review independently reads — keeps S06 and S07 as independent siblings realizing REL-01's shared authority, rather than creating an artificial dependency where S07 waits on S06's intermediate release/ artifact. This was questioned by the first final whole-branch review and re-affirmed after direct comparison against design.md's original reasoning.


**Invariants:**
- Release materialization reads approved snapshots only, independent of any prior S06 release-output artifact
- The install writes only inside the three declared managed roots (public/assets/vault, src/content, src/data/pages) and never touches any other site path
- A failed install leaves either the pre-call state or a fully-consistent post-call state -- no partial/torn state is ever observable to a caller, and a failed rollback itself blocks further installs rather than silently releasing the lock over an inconsistent tree
- Generated frontmatter is always valid, properly escaped YAML regardless of vault-authored title/description content

**Post-conditions:**
- [ ] publication-exporter test suite green at 324 tests
- [ ] real check-content.mjs gate green (2/2)
- [ ] real Astro build smoke test green (1/1)
- [ ] obsidian-plugin conformance suite green (69/70, 1 pre-existing unrelated skip)
- [ ] openspec validate --strict passes

## 3. Rationale

**Counterargument:** Chaining through S06's build-from-review output might look like the more obvious "pipeline" shape, and a reviewer unfamiliar with REL-01's authority model can mistake independent-siblings for an accidental omission.

**Selected variant weakest link:** The site-wide install lock (added mid-review to close a cross-identity manifest race) fully serializes every install regardless of publication identity — correct and cheap for a solo-operator CLI tool, but would need revisiting if this exporter is ever used for concurrent bulk/batch publishing.

**Rejected alternatives:**
| Variant | Verdict | Reason |
|---------|---------|--------|
| install-to-site: ManagedSiteInstaller port + FilesystemManagedSiteInstaller adapter reading ApprovedSnapshotWorkspace directly | **Selected** | Materializes the approved snapshot into the site's real m... |
| Chain install-to-site through S06's ReleaseOutputStore/build-from-review output | Rejected | REL-01 establishes that release materialization derives authority exclusively from the approved snapshot, not from any prior release artifact; chaining would create an artificial S06-to-S07 dependency the requirement doesn't demand and would complicate S10's future replace-generation design. |
| Keep the per-identity-only install lock (no site-wide lock) | Rejected | The second full whole-branch review found this allows two different identities' concurrent installs to race on the shared site-wide release-provenance manifest, silently dropping one side's content from the committed manifest; widened to a site-wide lock held across the full RU+EN+manifest commit boundary. |

**Evidence requirements:**
- Two full whole-branch reviews (gpt-5.6-sol, --effort xhigh) plus three scoped re-reviews, all Critical/Important findings resolved and independently verified by the controller (never just trusted from a report)
- Independent controller-run test verification after every fix: fast suite, real gate contract test, real Astro build smoke test, and the obsidian-plugin conformance suite

## 4. Consequences

**Rollback plan:**
Triggers:
- S10's replace-generation design finds the site-wide install lock or the direct-approved-snapshot-read architecture unworkable
- the deferred plugin review-UI gap becomes a genuine incident (an approver approves stale or wrong metadata without ever seeing it)
Steps:
1. Revert the full S07 commit range (from S06's archive commit through S07's own archive commit)
2. No later slice depends on install-to-site yet, so rollback is a clean revert with no cascading changes
Blast radius: publication-exporter module plus openspec/haft documentation; no site, plugin, or bridge-contract runtime changes to unwind since the Obsidian plugin itself was never modified by this slice

**Refresh triggers:**
- S10 designs replace-generation semantics for an existing site install
- The deferred Obsidian plugin review-UI slice is scoped, closing the known title/description review-visibility gap

**Affected files:** publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/site/ManagedSiteInstaller.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/site/SiteReleaseManifest.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/site/UnsafeManagedSiteEntryException.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/installtosite/InstallToSiteHandler.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/candidate/CandidateSnapshot.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/reference/ReferenceMap.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java, publication-exporter/src/main/java/dev/eugene/publicationexporter/bridge/ReviewPlan.java
