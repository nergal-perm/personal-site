# S23 Conditional Migration Apply and Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply a fresh all-inventory legacy migration under a durable journal, recover it explicitly, and admit semantic mode only for a complete coherent generation.

**Architecture:** The `legacy` package gains immutable generation values and narrow storage/locking ports. `MigrationApplyHandler` coordinates those collaborators through intention-revealing composed methods; null implementations run the same state transitions for tests, while filesystem adapters persist strict atomically replaced JSON. `SchemaActivationGuard` becomes a generation-integrity gate rather than a marker-syntax test.

**Tech Stack:** Java 17, Maven, JUnit 5, Jackson strict duplicate detection, Java NIO file locks and atomic moves.

**Spec:** `openspec/changes/s23-conditional-migration-apply-recovery/specs/legacy-transition/spec.md`; `openspec/changes/s23-conditional-migration-apply-recovery/design.md`

## Global Constraints

- Work directly on `master` and change production code only below `publication-exporter/`.
- Apply every identity from one fresh, blocker-free, ambiguity-free inventory; partial migration is not an S23 outcome.
- `--draft` is never decision or authorization input; apply/recovery remain explicitly requested CLI actions.
- Preserve pre-apply translations and approvals in immutable journal preimages until the generation is sealed or rolled back.
- Use strict JSON parsing, NOFOLLOW_LINKS confinement, atomic replacement, and a non-blocking global semantic-operation lock for filesystem state.
- Use no mocking framework: null collaborators retain observable state and all production domain code runs in unit tests.
- Use immutable values, `Objects.requireNonNull`, explicit empty collections/`Optional`, code-free constructors, short interfaces, and `try`-with-resources for locks.
- Keep public methods fewer than five per concrete class where the role permits; do not add inheritance or `instanceof` state switches.
- Do not commit without a separate explicit user request.

---

## File structure

- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationGeneration.java` — validated immutable generation identity, inventory fingerprint, ordered identities, cursor, and terminal state.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationJournalStore.java` / `NullMigrationJournalStore.java` / `FilesystemMigrationJournalStore.java` — journal port and implementations.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationCatalogStore.java` / `NullMigrationCatalogStore.java` / `FilesystemMigrationCatalogStore.java` — generation catalog port and implementations.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/SemanticOperationLock.java` / `NullSemanticOperationLock.java` / `FilesystemSemanticOperationLock.java` — scoped exclusive semantic mutation.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationWorkspace.java` / `NullMigrationWorkspace.java` / `FilesystemMigrationWorkspace.java` — capture, apply, and restore candidate/approved preimages.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationApplyHandler.java` — explicit apply/recovery orchestration.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/ActivationMarkerStore.java`, `FilesystemActivationMarkerStore.java`, `SchemaActivationGuard.java` — write marker and verify complete generation.
- `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java` plus its acceptance test — explicit apply/recovery argument boundary.
- `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/*Migration*Test.java` and `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/LegacyInventoryCliAcceptanceTest.java` — state and filesystem coverage.

### Task 1: In-memory generation and recovery state machine

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationGeneration.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationJournalStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/NullMigrationJournalStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationCatalogStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/NullMigrationCatalogStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/SemanticOperationLock.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/NullSemanticOperationLock.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationWorkspace.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/NullMigrationWorkspace.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationApplyHandler.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/MigrationApplyHandlerTest.java`

**Interfaces:**

```java
public interface SemanticOperationLock {
    <T> T exclusively(Supplier<T> operation);
}
public interface MigrationJournalStore {
    Optional<MigrationGeneration> read();
    void save(MigrationGeneration generation);
}
public final class MigrationApplyHandler {
    public MigrationGeneration apply(String humanDecisionJson);
    public MigrationGeneration rollForward();
    public MigrationGeneration rollBack();
}
```

- [ ] **Step 1: Write failing state-based tests**

```java
assertEquals(MigrationState.SEALED, handler.apply(decisions).state());
assertEquals(MigrationState.ROLLED_BACK, interrupted.rollBack().state());
assertThrows(SemanticOperationInProgressException.class, () -> locked.apply(decisions));
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml -Dtest=MigrationApplyHandlerTest test`

Expected: compilation failure because the generation/handler collaborators do not exist.

- [ ] **Step 3: Implement immutable values and null collaborators**

```java
public record MigrationGeneration(String inventorySha256, List<PublicationIdentity> identities,
        int completedSteps, MigrationState state) {
    public MigrationGeneration {
        Objects.requireNonNull(inventorySha256, "inventorySha256");
        identities = List.copyOf(identities);
        if (completedSteps < 0 || completedSteps > identities.size()) {
            throw new IllegalArgumentException("completedSteps must be within identities");
        }
    }
    public MigrationGeneration sealed() { return new MigrationGeneration(inventorySha256, identities, identities.size(), MigrationState.SEALED); }
}
```

Implement `MigrationApplyHandler` as composed methods: `validateBeforeMutation`, `startGeneration`, `applyRemainingIdentities`, and `sealGeneration`. `rollForward` consumes only journal state; `rollBack` restores recorded preimages. Each null store retains its resulting value so tests assert state, never collaborator calls.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml -Dtest=MigrationApplyHandlerTest test`

Expected: PASS for complete apply, preflight rejection, explicit forward/back recovery, and lock collision.

### Task 2: Strict filesystem journal, catalog, lock, and workspace

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemMigrationJournalStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemMigrationCatalogStore.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemSemanticOperationLock.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemMigrationWorkspace.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/ActivationMarkerStore.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/FilesystemActivationMarkerStore.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/FilesystemMigrationJournalStoreTest.java`
- Test: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/FilesystemMigrationWorkspaceTest.java`

**Interfaces:**

```java
public interface MigrationWorkspace {
    MigrationPreimage capture(MigrationGeneration generation);
    void apply(MigrationGeneration generation, int step);
    void restore(MigrationPreimage preimage);
}
```

- [ ] **Step 1: Write failing filesystem tests**

```java
assertEquals(running, MigrationJournalStore.create(root).read().orElseThrow());
assertThrows(MigrationJournalException.class, () -> store.read()); // duplicate JSON field
assertThrows(SemanticOperationInProgressException.class, () -> second.exclusively(() -> null));
```

- [ ] **Step 2: Run focused tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml -Dtest=FilesystemMigrationJournalStoreTest,FilesystemMigrationWorkspaceTest test`

Expected: compilation failure because filesystem migration stores do not exist.

- [ ] **Step 3: Implement filesystem behavior at the NIO boundary**

```java
private void replaceManifest(String manifestJson) {
    Path staging = manifest.resolveSibling(manifest.getFileName() + ".staging");
    Files.writeString(staging, manifestJson, UTF_8, CREATE_NEW);
    Files.move(staging, manifest, ATOMIC_MOVE, REPLACE_EXISTING);
}
```

Validate root confinement before every path operation; use `LinkOption.NOFOLLOW_LINKS`; parse with Jackson strict duplicate detection and reject trailing JSON tokens. Acquire the global lock with `FileChannel.tryLock()` inside `try`-with-resources. Capture snapshot preimages before the first workspace write and restore only those recorded values during roll-back.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml -Dtest=FilesystemMigrationJournalStoreTest,FilesystemMigrationWorkspaceTest test`

Expected: PASS for strict parsing, atomic state persistence, path confinement, lock collision, and deterministic restore.

### Task 3: Complete activation gate and explicit CLI boundary

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/SchemaActivationGuard.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/SchemaActivationGuardTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/LegacyInventoryCliAcceptanceTest.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/FilesystemMigrationAcceptanceTest.java`

**Interfaces:**

```java
public static SchemaActivationCheck check(ApprovedSnapshotWorkspace approved,
        CandidateWorkspace candidate, ActivationMarkerStore marker,
        MigrationJournalStore journal, MigrationCatalogStore catalog);
```

- [ ] **Step 1: Write failing complete-generation and CLI tests**

```java
assertTrue(SchemaActivationGuard.check(approved, candidate, marker, journal, catalog).isCurrent());
assertTrue(SchemaActivationGuard.check(approved, candidate, marker, unsealedJournal, catalog).isLegacy());
assertEquals(0, new CommandLine(new LegacyInventoryCommand()).execute("--review", root.toString(), "--apply", decisions.toString()));
```

- [ ] **Step 2: Run focused tests to verify they fail**

Run: `mvn -f publication-exporter/pom.xml -Dtest=SchemaActivationGuardTest,LegacyInventoryCliAcceptanceTest,FilesystemMigrationAcceptanceTest test`

Expected: compilation or assertion failure because marker-only admission and no apply/recovery command currently exist.

- [ ] **Step 3: Implement fail-closed composition and CLI mode validation**

```java
if (!journal.read().filter(MigrationGeneration::isSealed).isPresent()) {
    return SchemaActivationCheck.legacy("Migration is incomplete; explicitly roll forward or roll back.");
}
```

Add mutually exclusive `--apply`, `--roll-forward`, and `--roll-back` modes to the existing explicitly invoked command. `--apply` reads only a separate human decision file and delegates to `MigrationApplyHandler`; it never silently runs from inventory/draft mode. Pass filesystem ports constructed from the reviewed root.

- [ ] **Step 4: Run acceptance and full verification**

Run: `mvn -f publication-exporter/pom.xml test && openspec validate s23-conditional-migration-apply-recovery --strict && graphify update .`

Expected: Maven suite passes; OpenSpec is valid; Graphify refresh completes (or report only a sandbox permission blocker after retrying the approved elevated update).
