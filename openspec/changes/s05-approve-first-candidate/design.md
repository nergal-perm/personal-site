## Context

S04 left `inspect-publication` able to report a ready candidate and a first-publication review plan. S05 replaces the no-op `mark-reviewed` command (declared in `bridge-contract/schema-v2.json`'s enum, zero implementation anywhere) with a real handler that revalidates the S04-reviewed candidate and installs it as the first durable approved snapshot. This is greenfield: no approved-snapshot store, no reference-map read path, and no `mark-reviewed` Java code exist yet.

All code in this slice is governed by the same standing conventions as S01-S04 (memory `feedback-exporter-design-testing-conventions`): `/nullables` as the default testing technique, `/applying-sbpp` for class/method structure, `/oo-design-guide` for encapsulation/cohesion/coupling, and `/nullables`+`/applying-sbpp` interface-change discipline from `feedback-java-interface-change-task-planning` — every existing call site of any interface this slice touches is grepped and accounted for before tasks.md is finalized.

The functional collaborative-design pass found one real requirement gap (RVA-05's missing "second approval attempt" scenario, now in `specs/review-and-approval/spec.md`) and confirmed RVA-03, RVA-04, SEM-03, and BRG-01 are scope pins (`scope-pins.md`). The operator decided the approved snapshot gets its own new port (`ApprovedSnapshotWorkspace`), not an extension of `CandidateWorkspace`.

## Goals / Non-Goals

**Goals**
- `mark-reviewed` for the S04-reviewed candidate revalidates it is still exact (source unchanged, candidate files unchanged since `prepare`) and installs RU, EN, and the reference map as the first approved snapshot, returning `ok: true`, `status: "ready_to_publish"` only once durable (RVA-03, RVA-05).
- A second `mark-reviewed` for a publication that already has an approved snapshot fails closed with a diagnostic, not a silent replace or silent no-op (RVA-05's new scenario).
- Revalidation is real, not a rubber stamp: it re-admits the source note and compares fresh hashes against the hashes the candidate's own reference map recorded at `prepare` time, catching both source drift and candidate-file tampering (RVA-04).
- `references.json` becomes readable, not just writable, for the first time — `ReferenceMapCodec` gains a `read` method.

**Non-Goals** (deferred to later slices, per this change's `scope-pins.md`)
- Replacing an existing approved snapshot, and recovery from a crash mid-replacement (S09).
- Per-publication exclusion locking under real contention — nothing in this slice can attempt two concurrent approvals, so no lock primitive is built (S09).
- Release generation from the approved snapshot (S06).
- `inspect-publication` reporting the new approved-snapshot state — `approvedSnapshotState` stays hard-coded `"absent"` in this slice; wiring it up is not one of S05's introduced requirements (RVA-01/BRG-04 were already realized at S04 and are not reintroduced here).
- Non-empty reference-map occurrence validation (SEM-02/PCM-03, S13/S19) — every reference map this slice ever handles is the always-empty one S03 already produces.

## Decisions

### D1 — `ApprovedSnapshotWorkspace`: a new port, not an extension of `CandidateWorkspace`

```java
public interface ApprovedSnapshotWorkspace {
    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);
    Optional<CandidatePaths> find(PublicationIdentity identity);
    static ApprovedSnapshotWorkspace create(Path reviewRoot) { return new FilesystemApprovedSnapshotWorkspace(reviewRoot); }
    static ApprovedSnapshotWorkspace createNull() { return new NullApprovedSnapshotWorkspace(); }
}
```

Per the operator's explicit decision during the technical collaborative-design pass. Mirrors `CandidateWorkspace`'s exact shape and reuses `CandidatePaths` as the paths value type (approved snapshots have the identical RU/EN-path shape candidates do). The real adapter (`FilesystemApprovedSnapshotWorkspace`) reuses `CandidateWorkspace`'s proven conventions verbatim (Constructor Method, stage-then-`ATOMIC_MOVE`, a `requireWithinReviewRoot`-style confinement helper) but is its own class — this slice's one new production adapter, per the plan's slice-discipline budget.

**Alternatives considered:** extending `CandidateWorkspace` with `installApproved(...)`/`findApproved(...)`.

**Why not:** candidate and approved lifecycles are genuinely different and will diverge further, not converge — S09 adds replacement/atomic-recovery semantics and RVA-06 adds tamper detection, neither of which `CandidateWorkspace` needs. Folding both into one interface now trades a small amount of near-term duplication (two small adapter classes instead of one) for keeping each interface's single responsibility clean, and avoids touching the already-shipped, already-four-times-reviewed `CandidateWorkspace`/`FilesystemCandidateWorkspace` code at all.

### D2 — `CandidateWorkspace` gains `read(identity): Optional<CandidateSnapshot>`, alongside the existing paths-only `find(...)`

```java
public final class CandidateSnapshot {
    // ruBody, enBody, referenceMap — the actual content, not paths
    public static CandidateSnapshot of(String ruBody, String enBody, ReferenceMap referenceMap) { ... }
}
```

```java
public interface CandidateWorkspace {
    void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap);
    Optional<CandidatePaths> find(PublicationIdentity identity);       // unchanged, S04
    Optional<CandidateSnapshot> read(PublicationIdentity identity);    // new
    static CandidateWorkspace create(Path reviewRoot) { ... }
    static CandidateWorkspace createNull() { ... }
}
```

`mark-reviewed` needs the candidate's actual RU/EN body text and reference map — to revalidate hashes and to copy content into the approved snapshot — not just file paths. `find(...)` stays exactly as S04 left it (paths only, cheap, sufficient for the review-plan use case); `read(...)` is additive.

**Alternatives considered:** deriving `references.json`'s path from `CandidatePaths.ruPath().resolveSibling("references.json")` and reading files directly in `MarkReviewedHandler`, avoiding any `CandidateWorkspace` change at all.

**Why not:** this works for the real filesystem adapter but not for `NullCandidateWorkspace` — the in-memory fake has no real files backing its synthetic paths (S04's own design, D2/D4 there), so `Files.readString(...)` against a `Null*`-produced path would simply fail. Outside-in discipline requires the in-memory acceptance test to prove the contract first; that test needs a fake that can answer "what content is installed for this identity" without I/O. `read(...)` gives `NullCandidateWorkspace` an honest answer (return the `InstalledCandidate` content it already holds) and gives `FilesystemCandidateWorkspace` a real one (read the three files, parse the reference map) — both real implementations of the same query, not a path-privileged shortcut that only works for one adapter.

**Interface-change discipline (per `feedback-java-interface-change-task-planning`):** `CandidateWorkspace` currently has exactly two implementors (`NullCandidateWorkspace`, `FilesystemCandidateWorkspace`) plus one lambda-turned-anonymous-class test double in `PrepareHandlerTest` (from S04's own fix). All three are grepped and updated in the same task/commit that adds `read(...)` — tasks.md must not split this the way S04 initially (and incorrectly) split `find(...)` across two tasks.

### D3 — `ReferenceMapCodec` gains `read(String json): ReferenceMap`

```java
public static ReferenceMap read(String json) {
    JsonNode root = /* MAPPER.readTree(json) */;
    JsonNode identityNode = root.get("publicationIdentity");
    PublicationIdentity identity = PublicationIdentity.of(
            identityNode.get("publicCollection").asText(),
            identityNode.get("publicContentType").asText(),
            identityNode.get("publicId").asText());
    return ReferenceMap.empty(identity, root.get("ruHash").asText(), root.get("enHash").asText());
}
```

Manual tree-based parsing, not a Jackson-annotated deserializing constructor — matches this codebase's standing convention of keeping value types deserialization-agnostic (Constructor Method only, no bare `new`, no framework-driven construction). `occurrences` is read but not yet validated against anything, since every reference map this slice's own `write` path can produce has an empty one; occurrence consistency checking is SEM-02/S19's job. Only `FilesystemApprovedSnapshotWorkspace`'s and `FilesystemCandidateWorkspace`'s real `read(...)` implementations call this; `NullCandidateWorkspace`'s fake `read(...)` returns the `ReferenceMap` object it already holds in memory, no JSON round-trip involved.

### D4 — Revalidation: fresh source hash vs. the candidate's own recorded hashes, not a stored "prepare-time" side record

`MarkReviewedHandler.markReviewed(notePath, vaultReader)`:
1. `NoteIntake.admit(notePath, vaultReader)` — reused unchanged. Rejected → `BridgeResponse.blocked(...)`.
2. `candidateWorkspace.read(identity)` — absent → `BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate", "No candidate exists to approve."))`.
3. `approvedSnapshotWorkspace.find(identity)` — present → `BridgeResponse.blocked(COMMAND, Diagnostic.blocking("candidate", "An approved snapshot already exists; replacing it is not yet supported."))` (RVA-05's new scenario).
4. Compute `sha256Hex(intake.body())` (fresh source hash) and `sha256Hex(snapshot.ruBody())`/`sha256Hex(snapshot.enBody())` (fresh candidate-file hashes); compare all three against `snapshot.referenceMap().ruHash()`/`enHash()`. Any mismatch → `BridgeResponse.stale(COMMAND, diagnostics)` (D6).
5. All checks pass → `approvedSnapshotWorkspace.install(identity, snapshot.ruBody(), snapshot.enBody(), snapshot.referenceMap())`, then `BridgeResponse.approved(COMMAND, identity)`.

**Why compare against the reference map's own recorded hashes, not a separately stored "prepare-time snapshot":** `PrepareHandler` already computes `ruHash`/`enHash` from the exact bodies it writes into the candidate triple (`sha256Hex(ruBody)`/`sha256Hex(enBody)`, written into `references.json` via `ReferenceMap.empty(...)`) — the reference map already *is* the prepare-time hash record, per RVA-04's own "reference map... differ" clause. No new persistence mechanism is needed; `ReferenceMapCodec.read(...)` (D3) is what makes this record legible again.

**Alternatives considered:** only comparing fresh-source-hash against fresh-candidate-ru-hash (detects source drift, skipping the recorded-hash comparison entirely).

**Why not:** that alone cannot detect candidate-file tampering (`en.md` overwritten independently between `prepare` and `mark-reviewed`, with the source note untouched) — exactly the second half of RVA-04's "candidate or source changed" scenario. Comparing all three values against the recorded hashes catches both cases with one mechanism.

### D5 — `ApprovedSnapshotWorkspace#install` is create-only via an explicit existence check, not caller discipline

```java
final class FilesystemApprovedSnapshotWorkspace implements ApprovedSnapshotWorkspace {
    @Override
    public void install(PublicationIdentity identity, String ruBody, String enBody, ReferenceMap referenceMap) {
        Path destination = approvedDirectory(identity);
        if (Files.exists(destination)) {
            throw new ApprovedSnapshotAlreadyExistsException(identity);
        }
        // stage-then-ATOMIC_MOVE, identical shape to FilesystemCandidateWorkspace#install
    }
}
```

**Alternatives considered:** relying on `MarkReviewedHandler`'s own `approvedSnapshotWorkspace.find(identity)` check (D4 step 3) as the only guard, with `install(...)` unconditionally overwriting.

**Why not:** the port's own contract should make "create-only" true by construction, not by caller discipline — a future caller (or a future slice's handler) that forgets the `find(...)` check first would otherwise silently replace an approved snapshot, exactly the case S09 is supposed to own deliberately. `ApprovedSnapshotAlreadyExistsException` mirrors `CandidateWorkspaceConfinementException`'s existing pattern (a dedicated, narrowly-thrown exception type the handler catches and translates), not a generic `IllegalStateException`.

### D6 — `BridgeResponse` gains `approved(...)` and `stale(...)` factories; `blocked(...)` is reused unchanged for admission/no-candidate/already-approved

```java
public static BridgeResponse approved(String command, PublicationIdentity identity) {
    return new BridgeResponse(2, command, true, "ready_to_publish",
            List.of(), List.of(), identity, null, null, null, null, null);
}

public static BridgeResponse stale(String command, List<Diagnostic> diagnostics) {
    return new BridgeResponse(2, command, false, "stale",
            List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
}
```

Per the operator's decision that a successful approval reuses BRG-05's own eventual six-state vocabulary word (`"ready_to_publish"`) ahead of its formal introduction at S11 — the same precedent S04 set adopting `"ready_for_review"` early. `"stale"` is the matching vocabulary word for revalidation failure — also unused by any command until now, also adopted early rather than inventing a throwaway literal that S11 would need to reconcile. Both follow `translationFailed(...)`'s existing pattern: one factory per status literal, not a generalized `blocked(command, status, diagnostics)` that would let every caller invent new status strings ad hoc. The "no candidate to approve" and "already approved" failure cases reuse the existing `blocked(...)` factory (`"metadata_blocked"`) unchanged, differentiated only by diagnostic message — the same convention every existing `metadata_blocked` case already uses (unsafe path, note not found, missing source ID all share one status, distinguished by diagnostic text).

### D7 — Directory layout: `approved/` is a sibling of `candidate/`, same review root

`<reviewRoot>/<publicCollection>/<publicId>/approved/{ru.md,en.md,references.json}`, alongside the existing `.../candidate/{ru.md,en.md,references.json}`. `MarkReviewedCommand` builds both `CandidateWorkspace.create(reviewDirectory)` and `ApprovedSnapshotWorkspace.create(reviewDirectory)` from the same `--review` option `PrepareCommand`/`InspectPublicationCommand` already use — one physical review-workspace root, two independent stores within it, matching the plan's own "review-workspace" standing-adapter-category framing (D1's rationale for why this is still one conceptual root despite being two Java ports).

### D8 — CLI: `MarkReviewedCommand` mirrors `PrepareCommand`'s exact composition shape

```java
@Command(name = "mark-reviewed")
public final class MarkReviewedCommand implements Callable<Integer> {
    @Option(names = "--vault", required = true) Path vaultRoot;
    @Option(names = "--note", required = true) String notePath;
    @Option(names = "--review", required = true) Path reviewDirectory;
    @Option(names = "--jobs", required = true) Path jobsDirectory;   // accepted, unused — matches PrepareCommand's own current treatment
    @Option(names = "--json") boolean json;

    public Integer call() {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        BridgeResponse response = new MarkReviewedHandler(candidateWorkspace, approvedSnapshotWorkspace)
                .markReviewed(VaultRelativePath.of(notePath), vaultReader);
        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
```

`--jobs` is required (the plugin's `bridge-client.js` always sends it for `mark-reviewed`, per `COMMANDS["mark-reviewed"] = { note: true, jobs: true }`) but unused in this slice's `call()` body — identical to how `PrepareCommand` already declares and ignores it today. No plugin runtime code changes: `main.js`'s `markCurrentReviewed()` already calls `bridgeClient.run("mark-reviewed", file.path)` and only checks `result.ok`/`result.diagnostics`, both already satisfied by `approved(...)`/`stale(...)`/`blocked(...)`.

### D9 — No read-back-and-verify step after the atomic install

Per the operator's explicit decision: `ApprovedSnapshotWorkspace#install`'s stage-then-`ATOMIC_MOVE` guarantees atomic, all-or-nothing *visibility* by construction (matching `CandidateWorkspace#install`'s already-reviewed convention) — no failure mode in this slice's scope needs a defensive re-read to detect.

**Correction (post final-review):** this decision originally claimed ATOMIC_MOVE guarantees *durability*, conflating it with atomicity. `rename(2)` (what `ATOMIC_MOVE` uses on POSIX) guarantees you never observe a half-renamed state; it does not guarantee the renamed data survives a concurrent crash or power loss, which requires `fsync` on the staged files and the destination directory — neither of which this class performs. The requirement text (RVA-05) has been corrected to match (see `specs/review-and-approval/spec.md`'s "Spec correction" note); crash-survival durability remains unaddressed and out of scope for this slice.

## Risks / Trade-offs

- **[Risk]** `CandidateWorkspace` gains a third method (`read`), its second interface change in as many slices. **Mitigation:** all three known implementors/test-doubles are enumerated in this document (D2) and must be updated in one task/commit — the exact discipline `feedback-java-interface-change-task-planning` exists to enforce, checked here at design time rather than discovered at BLOCKED time.
- **[Risk]** Two new small filesystem-adapter classes (`FilesystemApprovedSnapshotWorkspace`, plus `CandidateWorkspace`'s new `read` path in `FilesystemCandidateWorkspace`) duplicate `FilesystemCandidateWorkspace#install`'s staging/confinement logic rather than sharing it. **Mitigation:** accepted per D1's reasoning — extracting a shared base now would be premature abstraction from two data points; revisit if a third store (e.g. a release-output store, S06) shows the same shape a third time.
- **[Risk]** `ReferenceMapCodec.read(...)`'s manual tree parsing has no test coverage yet for malformed/missing-field JSON. **Mitigation:** `read(...)` is only ever called on `references.json` files this codebase's own `write(...)` produced — targeted unit tests cover the round-trip (write → read) and the missing-field defensive case, not exhaustive malformed-input fuzzing (SEM-03's full duplicate/unknown/unused consistency checking is S19's job, once occurrences are non-empty).
- **[Risk]** `sha256Hex` currently lives as a private static helper duplicated only in `PrepareHandler`; `MarkReviewedHandler` needs the identical function. **Mitigation:** extract it to a small shared utility in this slice's implementation (a one-method class, e.g. `ContentHash.sha256Hex(String)`) rather than duplicating it a second time — a concrete, evidenced case for extraction, not speculative reuse.

## Migration Plan

Additive only — no existing users or data to migrate. Rollback is reverting the commit(s); S01-S04's existing behaviour, CLI option surface, and every existing test remain unchanged and green throughout.

## Open Questions

None outstanding. D1-D9 close every fork raised during the technical collaborative-design pass.
