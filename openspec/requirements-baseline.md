# Greenfield exporter requirements baseline

## Scope

This baseline specifies the externally observable behaviour of a replacement for the repository's publication exporter. It is derived from repository state at commit `aac0104` on 2026-08-03 and is local to the personal-site publication system represented by this repository.

The replacement exporter is the system under specification. The Obsidian plugin, vault, review workspace, translation worker, Astro site, and operator are neighbouring systems or actors. Existing Java classes are evidence of behaviour, not architectural requirements for the replacement.

## Normative interpretation

- `SHALL` and `SHALL NOT` state obligations on an implementation of the replacement exporter.
- `GIVEN` states the precondition and relevant state slice.
- `WHEN` states the stimulus or work requested by an actor.
- `THEN` states observable acceptance evidence.
- Each requirement is acceptance-bound by its scenarios. A requirement without a falsifiable scenario is incomplete.
- Repository defects and accidental implementation details are observations, not requirements. In particular, the current exporter emitting bridge schema v3 while the plugin accepts v2 is a defect to be repaired by the replacement contract.

## Controlled vocabulary

| Term | Strict meaning in these specifications |
| --- | --- |
| source note | Vault Markdown selected as the Russian authoring source. |
| publication identity | The pair `(publicCollection, publicId)` used to locate a publication; it is not semantic note identity. |
| source ID | Stable, unique, human-assigned semantic identity of a vault note; it never falls back to a path or public route. |
| candidate snapshot | A proposed RU/EN/reference set awaiting human approval. It has no release authority. |
| approved snapshot | The immutable RU/EN/reference set installed by explicit approval. |
| release projection | Derived public content materialized only from approved snapshots and current approved-target visibility. |
| review plan | A description of review artefacts and their freshness; it is not the human review work or approval. |
| selected | A source note is eligible for publication processing because its publication flag and identity fields pass admission. It does not mean approved or released. |
| blocked | A command completed safely without performing its requested state transition because an admission, validation, integrity, or safety predicate failed. |
| diagnostic | Structured evidence explaining an observed failure or warning; it is not workflow state. |

## Capability map

| Capability | External promise | Principal state owned |
| --- | --- | --- |
| publication-admission | Identify and validate exactly the intended publishable source notes. | Selection and preflight result. |
| public-content-model | Produce deterministic, safe, kind-specific public content from admitted notes. | Normalized publication manifest. |
| translation-preparation | Prepare a bounded candidate without changing approval or release state. | Translation job and candidate snapshot. |
| review-and-approval | Expose review evidence and advance the approved baseline only on explicit approval. | Review plan and approved snapshot. |
| semantic-references | Preserve stable note identity and activate approved links without rewriting approved referrers. | Reference map and approved-target projection. |
| release-materialization | Build deterministic, provenance-bound site input from approved state only. | Release projection and managed site trees. |
| workflow-bridge | Give the plugin and operator a stable, fail-closed control and observation surface. | Bridge response and workflow state. |
| legacy-transition | Inspect, decide, apply, and recover legacy semantic migration without weakening the core path. | Migration inventory, decision set, journal, and activation marker. |

## Evidence catalogue

The evidence IDs below point to live carriers. Tests are the strongest executable evidence; documentation and implementation corroborate intent and boundary shape.

| ID | Evidence carriers |
| --- | --- |
| E-ADM | `PublicationDiscoveryTest`, `PublicationSelectionTest`, `PreflightTest`, `PublicationContractTest`, `PublicationValidatorTest`, and `model/PublicationKind.java`. |
| E-CONTENT | `ManifestBuilderTest`, `LinkProcessorTest`, `AssetResolverTest`, `EditorialParserTest`, `MarkdownNormalizationTest`, and site content schemas. |
| E-PREP | `PrepareWorkflowTest`, `CodexRunnerTest`, `TranslationDiffTest`, `TranslationProjectionTest`, `TranslationValidatorTest`, and `exporter-java/README.md`. |
| E-REVIEW | `ReviewWorkspaceTest`, `ReviewLaunchPlannerTest`, `CandidateSnapshotStoreTest`, `PublishedSnapshotStoreTest`, `ApprovedSnapshotRepositoryTest`, and `AstroExportCommandTest`. |
| E-REF | `SemanticReferencePlannerTest`, `SemanticReferenceMarkdownTest`, `PageReferenceMapCodecTest`, `VaultReferenceResolverTest`, `VaultReferenceCatalogTest`, and `LateBoundSemanticLinksAcceptanceTest`. |
| E-REL | `ApprovedReleaseMaterializerTest`, `ReleaseProvenanceWriterTest`, `ReferenceImpactIndexTest`, `SiteWriterTest`, `site/scripts/check-content.mjs`, and `e2e/run-synthetic.sh`. |
| E-BRIDGE | `obsidian-plugin/bridge-client.js`, `obsidian-plugin/main.js`, `bridge-client.test.cjs`, `BridgeResponse.java`, `WorkflowStateServiceTest`, and `ReportBuilderTest`. |
| E-MIG | `ReferenceMigrationInventoryTest`, `ReferenceMigrationAlignerTest`, `SemanticMigrationServiceTest`, `SemanticOperationLockTest`, `SemanticSchemaStateTest`, and `exporter-java/README.md`. |
| E-GOV | `.haft/specs/`, active Haft decision records, and problem `prob-20260803-c1c6eca8`. |

## Explicit non-goals

- Reproducing the existing Java package structure, classes, or internal algorithms.
- Advancing an approved baseline during prepare, build, preview, deployment, inspection, or queue refresh.
- Treating Netlify deployment, Obsidian UI rendering, Zed itself, or translation-provider internals as exporter capabilities.
- Treating historical migration allocation rules as the normal identity-admission path.
- Preserving known defects such as bridge schema v3 incompatibility or native-image help failures.
