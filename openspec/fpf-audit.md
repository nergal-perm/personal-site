# FPF audit of the greenfield exporter requirements

## Audit attestation

- **Artifact:** the eight capability specifications under `openspec/specs/`.
- **BoundedContext:** `PersonalSite_Publication_2026-08-03`.
- **Observation window:** repository snapshot at commit `aac0104`, audited 2026-08-03.
- **Formality:** F4. Requirements are normative predicates and each is bound to the `H-OPENSPEC-GWT` acceptance harness: executable acceptance tests derived from the scenario precondition, stimulus, and observable result.
- **Evidence basis:** the carriers catalogued in `openspec/requirements-baseline.md`; requirements remain inferred until the user accepts them as the greenfield baseline.
- **FPF patterns applied:** A.1 holonic boundary discipline; A.2.2 capability; A.2.3 service clause; A.2.6 WorkScope; A.6.C contract unpacking; A.7 strict distinction; A.10 evidence graphs; B.1 aggregation; B.5 abductive-deductive-inductive reasoning; E.10.D1 lexical discipline; E.10.D2 Spec-gate.

## Reasoning cycle

1. **Abduction:** infer candidate externally observable promises from repository docs, commands, tests, scripts, plugin behaviour, site gates, and active Haft constraints.
2. **Deduction:** derive success, rejection, concurrency, integrity, and recovery consequences as Given-When-Then scenarios.
3. **Induction:** validate structure with OpenSpec and run the repository's executable suites. The observed results are recorded in `openspec/verification.md`. Passing existing tests corroborates fidelity to the old system; it does not prove the inferred requirements are the desired greenfield product, and pre-existing failures remain counterevidence to accidental idealization.

## Completeness audit

### Lifecycle coverage

| Lifecycle slice | Capability | Covered observable outcomes |
| --- | --- | --- |
| Select | publication-admission | discovery, path confinement, identity, supported kind, bounded versus aggregate validation, authoring contract |
| Normalize | public-content-model | manifest, kind fields, links, protected Markdown, assets, bilingual structure |
| Prepare | translation-preparation | bounded job, baseline diff, candidate preservation, job isolation, occurrence identity, workflow scalar edit |
| Review | review-and-approval | read-only inspection, review plan, exact revalidation |
| Approve | review-and-approval | sole authority, atomic durable install, immutability and tamper detection |
| Resolve | semantic-references | source-owned identity, occurrence mapping, reference-map integrity, late binding, no referrer reapproval |
| Release | release-materialization | approved-only input, safe bilingual projection, provenance, input guard, atomic managed trees, site gate |
| Operate | workflow-bridge | command boundary, schema v2, shared contract, independent states, six-state vocabulary, queue refresh, editor launch |
| Transition | legacy-transition | explicit entry, inert inventory, human decisions, locked apply/recovery, fail-closed activation |

### Path-condition coverage

Each capability contains positive and rejection scenarios. State-mutating capabilities additionally cover at least one concurrency, interruption, tamper, or recovery path. Cross-capability safety is not hidden in a generic “security” capability: the relevant promise owns its own safety and evidence predicates.

### Repository-surface coverage

| Evidence surface | Requirements consuming it | Audit result |
| --- | --- | --- |
| CLI and bridge responses | BRG-01 through BRG-07, RVA-01, RVA-03 | Covered, including the v2/v3 incompatibility as a defect rather than a target behaviour. |
| Discovery and validation | ADM-01 through ADM-06 | Covered. |
| Manifest, links, assets, editorial grammar | PCM-01 through PCM-06 | Covered at behavioural level; exact YAML serialization quirks are intentionally not architectural requirements. |
| Translation worker and jobs | TRP-01 through TRP-06 | Covered with provider-neutral acceptance boundaries. |
| Candidate and approved review workspace | RVA-01 through RVA-06 | Covered with candidate/approved distinction and recovery. |
| Stable semantic references | SEM-01 through SEM-05 | Covered and aligned with active Haft identity constraints. |
| Release, site writing, and site content gate | REL-01 through REL-06 | Covered; deployment is outside exporter scope. |
| Historical migration surfaces | MIG-01 through MIG-05 | Covered conditionally, without importing migration mechanics into the normal path. |

### Completeness verdict

The capability set is complete relative to the observed repository's exporter-facing behaviour: every production package and acceptance surface maps to at least one capability, and the whole author-to-site lifecycle has success and fail-closed paths. Completeness is bounded by the snapshot and evidence catalogue; it is not a claim that unimplemented future wishes have been discovered.

## Unambiguity audit

### Repaired ambiguous terms

| Ambiguous phrase in ordinary project usage | Strict replacement |
| --- | --- |
| published note | selected source note, approved snapshot, or released page, whichever is meant |
| publication status | one named workflow state plus independent candidate/approved/reference/release dimensions |
| baseline | approved snapshot, never candidate or source |
| ID | publication identity, source ID, occurrence ID, or job ID |
| link exists | authored occurrence, approved occurrence, or resolved release link |
| review | review plan, human review work, or explicit approval work |
| exporter contract | service clause, versioned JSON description, implementation obligation, or conformance evidence |
| current/latest | snapshot commit and observation date |

The controlled vocabulary in `requirements-baseline.md` is normative for these specifications. Scenarios name state preconditions and observable carriers instead of relying on adjectives such as “valid”, “ready”, or “safe” alone. Where those adjectives remain, the same requirement enumerates their deciding predicates.

### Unambiguity verdict

No normative requirement intentionally uses “publish”, “baseline”, “ID”, “review”, or “state” without a qualified object. The bridge schema is pinned to major version 2 for the initial replacement release rather than described as “compatible with the plugin”.

## Cohesiveness audit

### Capability test

Each capability is an ability of the replacement exporter under a declared WorkScope, not a role, class, command, method, work occurrence, or document:

- Admission decides membership and validity.
- Content modelling derives a normalized public representation.
- Preparation creates a candidate.
- Review and approval adjudicate and advance authority.
- Semantic references preserve identity and defer visibility projection.
- Release materialization creates approved public output.
- Workflow bridge exposes control and observation.
- Legacy transition changes workspace schema under exceptional authority.

The boundaries follow state authority, not the current Java package layout. Dependencies are directed:

`admission -> content model -> preparation -> review/approval -> release`, with semantic references contributing to preparation, approval, and release, and the bridge observing or initiating work without owning domain state.

### Aggregation checks

- **Idempotence:** repeating read-only inspection, inventory, or deterministic materialization against unchanged inputs yields the same observation or output.
- **Local commutativity:** independently preparing or inspecting different publication identities does not change their outcomes; per-publication locks expose genuine dependencies.
- **Locality:** filesystem enumeration order and worker location do not alter normalized outputs; declared input hashes determine outcomes.
- **Weakest-link bound:** an unsafe path, incomplete approved triple, invalid reference map, or failed provenance member blocks the aggregate release rather than being averaged away.
- **Monotonicity qualification:** approving a target can increase link activation without weakening referrer integrity. Unpublishing a target intentionally decreases visibility, so release projection is a context-sensitive aggregation rather than a monotone approval score.

### Cohesiveness verdict

The eight files are coarse enough for lifecycle ownership yet small enough for a short-context agent to load one capability plus its direct dependencies. A standalone “integrity” capability was rejected because it would collect unrelated promises and detach safety criteria from the work they adjudicate.

## Strictness audit

### Enforced distinctions

1. Source note is not its path, public route, candidate encoding, approved encoding, or released page.
2. Publication identity is not stable semantic source identity.
3. `publish: true` admits work; it does not approve or release content.
4. Candidate snapshot is not approved snapshot; approved snapshot is not release projection.
5. A review plan is a description encoded in a response; it is not human review work or approval work.
6. A schema document is a contract carrier; it does not itself perform validation or promise compatibility.
7. A diagnostic is evidence about a state decision, not another workflow state.
8. Historical migration work is not the normal source-identity admission method.
9. Existing Java code and tests are evidence carriers, not the greenfield object model.
10. Site deployment is downstream work, not release-materialization authority.

### Contract unpacking

For the plugin boundary:

- **Service clause:** command requests receive one interpretable, versioned result.
- **Published description:** the single-sourced bridge schema and command vocabulary.
- **Implementation commitments:** exporter emits conforming v2 responses; plugin validates them and fails closed on mismatch.
- **Work and evidence:** command process, JSON carrier, exit status, diagnostics, and shared conformance tests.

For approval:

- **Service clause:** an exact reviewed candidate can become the durable baseline.
- **Published description:** RVA requirements and response fields.
- **Implementation commitment:** only explicit approval work advances the baseline after revalidation.
- **Work and evidence:** lock, hashes, atomic install, recovery journal, and post-install inspection.

### Strictness verdict

The specifications avoid attributing agency to files, schemas, interfaces, or snapshots. Obligations name the exporter or plugin integration; evidence scenarios identify observable work products. No current defect was promoted into a requirement.

## Residual decisions and bounded gaps

These are deliberately not guessed into normative requirements:

1. **Shared bridge carrier:** choose the concrete source-of-truth format for schema v2 and its code-generation or validation strategy.
2. **Cutover policy:** decide whether the replacement initially reads legacy approved pairs or requires all active workspaces to complete `legacy-transition` first.
3. **Translation delivery:** choose the worker protocol and retry/time budgets while preserving TRP's provider-neutral contract.
4. **Native distribution:** choose supported operating systems, packaging, and help/reflection acceptance; the current native failure is evidence of need, not a chosen platform matrix.
5. **Compatibility depth:** decide which byte-level legacy YAML/Markdown quirks are required beyond the semantic and site-acceptance requirements.
6. **Editorial evolution:** decide whether the current finite editorial grammar remains one contract edition or becomes independently versioned.

These decisions affect implementation and compatibility, not the validity of the capability partition. They should be resolved through explicit Haft exploration and decision records before implementation planning.

## Final FPF verdict

- **Completeness:** passes for the bounded repository snapshot; all observed exporter lifecycle surfaces have capability ownership and acceptance paths.
- **Unambiguity:** passes after controlled-term and version pinning; residual choices are listed rather than hidden.
- **Cohesiveness:** passes; capabilities aggregate by external promise and state authority, not package structure.
- **Strictness:** passes; object, description, carrier, work, evidence, candidate, approval, and release layers remain distinct.

The requirements are ready for user review and Haft-governed decision work. They are not yet evidence that a replacement implementation conforms.
