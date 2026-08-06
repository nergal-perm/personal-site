## Context

S05 left a durable, atomically-installed approved snapshot (`ApprovedSnapshotWorkspace#install`/`#find`, `ru.md`/`en.md`/`references.json` under `<reviewRoot>/<collection>/<id>/approved/`) but no way to turn it into anything a site can build from. S06 adds `build-from-review`: read the approved triple for one publication and write one RU essay file, one EN essay file, and deterministic minimum release provenance into a brand-new, previously empty output root, ignoring any candidate. This is greenfield: no release-output port, no provenance concept, and no `build-from-review` code exist anywhere yet.

All code in this slice is governed by the same standing conventions as S01-S05 (memory `feedback-exporter-design-testing-conventions`): `/nullables` as the default testing technique, `/applying-sbpp` for class/method structure, `/oo-design-guide` for encapsulation/cohesion/coupling, and the interface-change discipline from `feedback-java-interface-change-task-planning` — every existing implementor of any interface this slice touches is grepped and updated in the same task/commit.

The functional collaborative-design pass found one real requirement gap (REL-02's missing "no semantic occurrences" scenario, now in `specs/release-materialization/spec.md`) and confirmed REL-01, REL-03 (determinism half), and PCM-01/PCM-02 are scope pins (`scope-pins.md`). This technical pass resolves three facts by direct evidence rather than by asking the operator to guess:

1. **Approved snapshot content is plain body text, no frontmatter.** `PrepareHandler.prepareAdmittedEssay` stores `intake.body()` verbatim as `ruBody` and the raw `TranslationResult#enBody()` as `enBody` — no title, date, or tags exist anywhere in the codebase (`EssayAdmission` captures only identity + `sourceId`). S06's release essay files can only ever contain that same plain body text; reconstructing Astro front matter is not a requirement any baseline capability introduces yet, so it is out of scope here, not deferred by oversight.
2. **`build-from-review` is not a bridge command.** `bridge-contract/schema-v2.json`'s `command` enum is `["prepare", "inspect-publication", "mark-reviewed", "refresh-publication-queue"]`. `build-from-review` is an operator/CLI-only action the Obsidian plugin never invokes and no conformance test will ever exercise — it must not produce a `BridgeResponse`.
3. **`ApprovedSnapshotWorkspace` has three implementors today**, not two: `NullApprovedSnapshotWorkspace`, `FilesystemApprovedSnapshotWorkspace` (both `src/main`), and one anonymous test double, `MarkReviewedHandlerTest#approvedSnapshotWorkspaceThrowing` (line ~247-263). All three are updated in the same commit that adds a new interface method (D1).

## Goals / Non-Goals

**Goals**
- `build-from-review`, given a publication identity with a durable approved snapshot, writes one RU essay file and one EN essay file, byte-identical to the approved bodies, into a fresh output root, plus a deterministic minimum provenance record binding contract edition, approved-snapshot hashes, and output-file hashes (REL-01, REL-03).
- A publication with no approved snapshot blocks before any output write — no partial output root is ever left behind (REL-01).
- Any existing candidate is never read — only `ApprovedSnapshotWorkspace` has release authority (REL-01).
- Building from the same approved state twice, into two different fresh output roots, produces byte-identical release files and normalized provenance (REL-03).
- Release output for a zero-occurrence approved snapshot emits the approved body unchanged and leaks no semantic token, source ID, private vault path, or internal private route (REL-02's new scenario) — true by construction since no transformation happens between approved bytes and output bytes.

**Non-Goals** (deferred to later slices, per this change's `scope-pins.md`)
- Replacing an existing release generation, or recovery from an interrupted replacement (S10).
- Installing into `site/src/content/` or any other live-site tree, and re-laying the release tree out into the site's `<collection>/<locale>/<id>.md` shape (S07).
- Assets, links, and multiple publications in one invocation (S13, S14, S16).
- Resolving semantic occurrences into public routes (S20) — there are never any occurrences to resolve in this slice.
- Reconstructing Astro front matter (title, date, tags, description) — no requirement introduces this yet; release files remain plain body text.

## Decisions

### D1 — `ApprovedSnapshotWorkspace` gains `read(identity): Optional<CandidateSnapshot>`, reusing S05's `CandidateSnapshot`

```java
public interface ApprovedSnapshotWorkspace {
    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);
    Optional<CandidatePaths> find(PublicationIdentity identity);       // unchanged, S05
    Optional<CandidateSnapshot> read(PublicationIdentity identity);    // new
    static ApprovedSnapshotWorkspace create(Path reviewRoot) { ... }
    static ApprovedSnapshotWorkspace createNull() { ... }
}
```

`build-from-review` needs the approved RU/EN body text and reference map (for the snapshot's own hashes), not just file paths — the same gap `CandidateWorkspace#read` closed for `mark-reviewed` in S05. Reusing `CandidateSnapshot` as-is (not a new `ApprovedSnapshot` type) is deliberate: approved and candidate content share the exact same shape (`ruBody`, `enBody`, `referenceMap`), and inventing a second identical value type would be duplication with no behavioral difference to justify it.

**This is the interface-change task.** All three known implementors/test-doubles are updated in one commit:
- `NullApprovedSnapshotWorkspace` currently stores only `Map<PublicationIdentity, ReferenceMap>` (S05 deliberately dropped body storage — "nothing in this slice's acceptance tests reads approved body content back out"). That is no longer true; it now stores an `InstalledApprovedSnapshot(identity, ruBody, enBody, referenceMap)` per identity, mirroring `NullCandidateWorkspace.InstalledCandidate` exactly.
- `FilesystemApprovedSnapshotWorkspace` gains `read(...)`, mirroring `FilesystemCandidateWorkspace#read` exactly (read `ru.md`/`en.md`/`references.json`, absent if any is missing, `ReferenceMapCodec.read(...)` for the map).
- `MarkReviewedHandlerTest#approvedSnapshotWorkspaceThrowing` (anonymous class, only overrides `install`/`find` today) gains a `read` override throwing the same injected failure, for compile-time completeness — it is never called by `MarkReviewedHandler`, which only uses `find`.

**Alternatives considered:** deriving `references.json`'s path from `CandidatePaths` and reading files directly in `BuildFromReviewHandler`.

**Why not:** identical reasoning to S05's D2 — this breaks `NullApprovedSnapshotWorkspace`, which has no real files backing its synthetic paths. The in-memory acceptance test must prove the contract without I/O.

### D2 — `ReleaseOutputStore`: this slice's one new production boundary adapter

```java
public interface ReleaseOutputStore {
    void install(PublicationIdentity identity, String ruBody, String enBody, ReleaseProvenance provenance);
    static ReleaseOutputStore create(Path outputRoot) { return new FilesystemReleaseOutputStore(outputRoot); }
    static ReleaseOutputStore createNull() { return new NullReleaseOutputStore(); }
}
```

Layout: `<outputRoot>/<publicCollection>/<publicId>/release/{ru.md,en.md,release-provenance.json}` — the same identity-scoped-fresh-directory convention `CandidateWorkspace`/`ApprovedSnapshotWorkspace` already established, with `"release"` as the leaf name. `install(...)` is create-only (throws `ReleaseAlreadyExistsException` if the `release/` directory already exists), stage-then-`ATOMIC_MOVE`, confined to `outputRoot` via a `requireWithinOutputRoot`-style helper — the exact shape D1/D5 of S05's design.md established for `ApprovedSnapshotWorkspace`.

**Why this layout, not the site's `<collection>/<locale>/<id>.md` shape:** the real Astro content tree groups files by `<collection>/<locale>/`, a directory shared across every publication in that collection — evidence: `site/src/content/blog/ru/essay-tdd-fractality.md`. Mirroring that shape now would mean the release adapter's destination directory (`<outputRoot>/blog/ru/`) already exists on every publication after the first, breaking the create-only atomic-whole-directory-move trick this slice's two sibling adapters both rely on. S06 has exactly one publication in scope (multiple publications are explicitly excluded), so either layout works today — but choosing the identity-scoped layout defers the real "how do many publications share one locale directory, and how does that map onto the live site" question to S07/S16, where it can be decided with actual multi-publication evidence, instead of guessing it here. S07's install step is where the re-layout into `site/src/content/` happens; it was always going to need a transformation step, not a plain tree copy, regardless of this choice.

**Alternatives considered:** writing directly into the `<collection>/<locale>/<id>.md` shape.

**Why not:** see above — it borrows a multi-publication decision this slice has no evidence to make well, and it does not save S07 any real work (S07 still installs into `site/src/content/`, a different root entirely, and still needs its own gate/provenance-check logic regardless of the staging shape).

### D3 — `FilesystemCandidateWorkspace`, `FilesystemApprovedSnapshotWorkspace`, and `FilesystemReleaseOutputStore` share one small internal staging helper

S05's design.md flagged this exact trade-off: "revisit if a third store shows the same shape a third time." `FilesystemReleaseOutputStore` is that third store — identical stage-in-temp-dir, confine-to-root, `ATOMIC_MOVE`-into-a-not-yet-existing-destination, best-effort-cleanup-on-failure shape as the other two. This slice extracts a package-private helper:

```java
package dev.eugene.publicationexporter.fs;

final class StagedDirectoryInstall {
    private final Path canonicalRoot;
    StagedDirectoryInstall(Path root) { this.canonicalRoot = canonicalize(root); }
    Path createStaging() { ... }                          // Files.createTempDirectory(canonicalRoot, ...)
    void publish(Path staging, Path destination) { ... }   // confinement check + createDirectories(parent) + ATOMIC_MOVE
    void requireWithin(Path candidate) { ... }              // the confinement check, parameterized by canonicalRoot
    static void deleteRecursively(Path root) { ... }        // best-effort cleanup
}
```

Each Filesystem adapter keeps its own public exception type (`CandidateWorkspaceConfinementException`, `ApprovedSnapshotWorkspaceConfinementException`, new `ReleaseOutputStoreConfinementException`) and its own file-naming/existence-check logic — only the staging/move/confinement mechanics move into the shared helper. This is a pure behavior-preserving refactor: `FilesystemCandidateWorkspaceTest` and `FilesystemApprovedSnapshotWorkspaceTest` pass unchanged before and after, proving no observable behavior moved.

**Alternatives considered:** duplicating the shape a third time, as S05 explicitly permitted ("premature abstraction from two data points").

**Why not duplicate again:** two data points can be coincidence; three, with byte-identical structure and no independent evolution in between, is the evidence threshold S05's own risk note set for when to stop duplicating. Declining to extract now would mean carrying the exact same ~30-line staging dance a third time with no remaining justification.

**Why not go further** (e.g., unifying the three public ports into one generic `StagedTripleWorkspace<T>`): the three ports still write a different number/shape of files (approved/candidate: fixed `ru.md`/`en.md`/`references.json`; release: `ru.md`/`en.md`/`release-provenance.json`) and will diverge further — S09/S10 add replace/recovery semantics to approved/release that candidate will likely never need. Only the low-level filesystem mechanics are shared; the public contracts stay distinct, per the same reasoning S05's D1 gave for keeping `ApprovedSnapshotWorkspace` and `CandidateWorkspace` separate interfaces.

### D4 — `ReleaseProvenance`: a small Whole Value, not `exporter-java`'s `ReleaseProvenance` record

```java
public final class ReleaseProvenance {
    private static final int CONTRACT_EDITION = 1;

    private final PublicationIdentity identity;
    private final String approvedRuHash;
    private final String approvedEnHash;
    private final String outputRuHash;
    private final String outputEnHash;

    private ReleaseProvenance(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash) { ... }

    public static ReleaseProvenance of(PublicationIdentity identity, String approvedRuHash, String approvedEnHash,
            String outputRuHash, String outputEnHash) { ... }

    @JsonProperty("contractEdition") public int contractEdition() { return CONTRACT_EDITION; }
    @JsonProperty("publicationIdentity") public PublicationIdentity identity() { return identity; }
    @JsonProperty("approvedRuHash") public String approvedRuHash() { return approvedRuHash; }
    @JsonProperty("approvedEnHash") public String approvedEnHash() { return approvedEnHash; }
    @JsonProperty("outputRuHash") public String outputRuHash() { return outputRuHash; }
    @JsonProperty("outputEnHash") public String outputEnHash() { return outputEnHash; }
    @JsonProperty("activationCount") public int activationCount() { return 0; }
    @JsonProperty("deactivationCount") public int deactivationCount() { return 0; }
    // equals/hashCode/toString over all five stored fields
}
```

Constructor Method only (matches `PublicationIdentity`/`ReferenceMap`/`CandidateSnapshot` — never a bare `new` outside the class, never a `record`). `activationCount`/`deactivationCount` are hard-coded `0`, not stored fields — there is no semantic-occurrence machinery to produce a non-zero value until S19/S20, and hard-coding makes that fact explicit in the type rather than implicit in every caller passing `0`.

**Why record both `approvedRuHash`/`approvedEnHash` and `outputRuHash`/`outputEnHash` when they are always equal today:** REL-03 requires provenance to bind "selected approved snapshot hashes" and "hashes of every managed output tree and file" as separate facts, not one fact recorded twice. `outputRuHash`/`outputEnHash` are computed fresh, from the exact bytes handed to `ReleaseOutputStore#install`, at write time — not copied from the reference map. This makes "output matches approved" a checked equality the acceptance test asserts, not an assumption baked into the type; the day a future slice stops copying bytes verbatim (e.g., a release-time re-projection step), this pair of fields immediately diverges without any code change here, giving S10's future tamper-detection work a real signal to compare against.

**Alternatives considered:** `exporter-java`'s richer `ReleaseProvenance` record (`selectedPages`, `managedTrees`, `managedFiles`, `payloadDigest`).

**Why not:** evidence only, not a template — that shape exists to support multiple publications, asset trees, and a real site-content gate, none of which exist yet. Carrying those fields now, always empty or trivial, would be exactly the "abstraction pulled in for speculative reuse" the plan's slice discipline forbids.

### D5 — `BuildFromReviewHandler` produces its own `ReleaseResult`, not a `BridgeResponse`

```java
public final class BuildFromReviewHandler {
    public ReleaseResult buildFromReview(PublicationIdentity identity) {
        Optional<CandidateSnapshot> approved = approvedSnapshotWorkspace.read(identity);
        if (approved.isEmpty()) {
            return ReleaseResult.blocked("No approved snapshot exists to release.");
        }
        CandidateSnapshot snapshot = approved.get();
        String outputRuHash = ContentHash.sha256Hex(snapshot.ruBody());
        String outputEnHash = ContentHash.sha256Hex(snapshot.enBody());
        ReleaseProvenance provenance = ReleaseProvenance.of(identity,
                snapshot.referenceMap().ruHash(), snapshot.referenceMap().enHash(),
                outputRuHash, outputEnHash);
        try {
            releaseOutputStore.install(identity, snapshot.ruBody(), snapshot.enBody(), provenance);
        } catch (ReleaseAlreadyExistsException raceLoser) {
            return ReleaseResult.blocked("A release already exists at this output root; replacing it is not yet supported.");
        } catch (UncheckedIOException failure) {
            return ReleaseResult.blocked("Release installation failed.");
        }
        return ReleaseResult.released(identity, provenance);
    }
}
```

```java
public final class ReleaseResult {
    public static ReleaseResult released(PublicationIdentity identity, ReleaseProvenance provenance) { ... }
    public static ReleaseResult blocked(String message) { ... }
    @JsonProperty("ok") public boolean ok() { ... }
    @JsonProperty("identity") public PublicationIdentity identity() { ... }      // null when blocked
    @JsonProperty("provenance") public ReleaseProvenance provenance() { ... }    // null when blocked
    @JsonProperty("message") public String message() { ... }                    // null when released
}
```

Per the settled evidence in Context: `build-from-review` is absent from `bridge-contract/schema-v2.json`'s command enum, so it is never consumed by the Obsidian plugin and no conformance test will ever exercise it. Reusing `BridgeResponse`'s `schemaVersion`/`status`/`diagnostics` vocabulary here would imply a plugin contract that does not exist and cannot be validated. `ReleaseResult` is a small, independent value type serialized the same way (`ObjectMapper#writeValueAsString`) for CLI JSON output, with no schema-v2 obligations.

**Alternatives considered:** reusing `BridgeResponse.blocked(...)`/inventing a new `BridgeResponse.released(...)` factory.

**Why not:** `BridgeResponse`'s whole reason to exist is schema-v2 plugin compatibility (BRG-02/BRG-03). Extending its vocabulary for a command the plugin never calls would be a false signal to any future reader of `BridgeResponse` that every one of its factories is plugin-relevant.

### D6 — CLI: `BuildFromReviewCommand` takes identity fields directly, not `--vault`/`--note`

```java
@Command(name = "build-from-review")
public final class BuildFromReviewCommand implements Callable<Integer> {
    @Option(names = "--review", required = true) Path reviewDirectory;
    @Option(names = "--output", required = true) Path outputRoot;
    @Option(names = "--collection", required = true) String collection;
    @Option(names = "--content-type", required = true) String contentType;
    @Option(names = "--id", required = true) String publicId;

    public Integer call() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        ReleaseOutputStore releaseOutputStore = ReleaseOutputStore.create(outputRoot);
        PublicationIdentity identity = PublicationIdentity.of(collection, contentType, publicId);
        ReleaseResult result = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore)
                .buildFromReview(identity);
        System.out.println(new ObjectMapper().writeValueAsString(result));
        return result.ok() ? 0 : 1;
    }
}
```

Every other command (`--vault`, `--note`, `--review`/`--jobs`) admits a vault note first and derives identity from its front matter. `build-from-review` has no note to admit — its only input is an already-approved snapshot, keyed by identity — so identity is supplied directly as three explicit options mirroring `PublicationIdentity`'s three fields, matching this codebase's standing preference for explicit named options over a composite/parsed identity string. Registered on `Main` alongside the existing three subcommands.

**Alternatives considered:** a single `--publication blog/essay/my-essay` composite option, parsed and split on `/`.

**Why not:** every other command already prefers one flag per concept over a parsed composite (`--vault`+`--note`, not `--source vault:note`); introducing a parsing convention here for three fields that are already a clean value object would be inconsistent for no benefit.

## Risks / Trade-offs

- **[Risk]** `ApprovedSnapshotWorkspace` gains a second method (`read`), its second interface change in as many slices, following `CandidateWorkspace`'s own second change in S05. **Mitigation:** all three known implementors/test-doubles are enumerated in D1 and updated in one commit — the exact discipline `feedback-java-interface-change-task-planning` exists to enforce.
- **[Risk]** The D3 extraction touches two already-shipped, already-reviewed files (`FilesystemCandidateWorkspace`, `FilesystemApprovedSnapshotWorkspace`) in a slice whose own new behavior lives elsewhere. **Mitigation:** the refactor is behavior-preserving by construction (stage/move/confinement mechanics only, no public API or file-naming change) and is proven so by both files' existing test suites passing unchanged; if the extraction proves awkward mid-implementation, it can be dropped for a third duplicated copy without blocking this slice's acceptance test, and revisited later.
- **[Risk]** `NullApprovedSnapshotWorkspace` changes its internal storage shape (`Map<Identity, ReferenceMap>` to a body-carrying record), a behavior change to test-only code from S05. **Mitigation:** its existing four tests (install/find/second-install/factory) assert only `find(...)`'s paths-and-existence contract, unaffected by what is additionally stored; no existing assertion inspects the map's internal shape.
- **[Risk]** The release output layout (D2) intentionally does not match the live site's `<collection>/<locale>/<id>.md` shape, deferring that mapping to S07. **Mitigation:** this is a deliberate, evidenced choice (see D2's rationale), not an oversight — S07's own design pass owns the install-time re-layout and multi-publication directory-sharing question with real evidence from S16, rather than this slice guessing at it with one publication in scope.

## Migration Plan

Additive only — no existing users or data to migrate. Rollback is reverting the commit(s); S01-S05's existing behavior, CLI option surface, and every existing test remain unchanged and green throughout, aside from the internal (non-public-API) staging refactor in D3, which existing tests prove behavior-preserving.

## Open Questions

None outstanding for this slice. D1-D6 close every fork raised during the technical collaborative-design pass; D2 and D3 explicitly name the two decisions most likely to be revisited once S07/S09/S10 add real evidence (multi-publication layout, and replace/recovery semantics), rather than leaving them silently assumed.
