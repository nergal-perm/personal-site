# S22 Non-executable Migration Decisions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make S21 legacy inventory produce a deterministic non-executable draft and make a distinct human decision file eligible only when its strict JSON schema and fresh inventory fingerprint validate.

**Architecture:** Keep the in-memory workflow in `legacy`: immutable values describe a minimal decision set and a draft, a codec owns JSON shape, and a validator re-inspects the workspace before accepting a human file. Extend `legacy-inventory` with mutually exclusive read-only draft and validation options; it never calls an approval, candidate, catalog, activation, or migration writer.

**Tech Stack:** Java 17 records/final classes, Jackson 2.22 strict duplicate detection, Picocli, JUnit 5, Maven.

**Spec:** `openspec/changes/s22-non-executable-migration-decisions/specs/legacy-transition/spec.md`; `openspec/changes/s22-non-executable-migration-decisions/design.md`

## Global Constraints

- Implement only MIG-03 in `publication-exporter`; `exporter-java` is out of scope.
- Preserve existing translations and approvals: S22 is read-only with respect to the review workspace.
- A generated draft has the fixed marker `draftOnly: true`; no generated JSON is executable.
- An eligible human file is a distinct strict JSON object containing exactly `schemaVersion: 1` and a 64-character lowercase hexadecimal `inventorySha256`.
- Re-inspect the review workspace for every validation; compare that new fingerprint before reporting success.
- Reject duplicate JSON fields, unknown/missing fields, wrong shapes, draft markers, malformed fingerprints, and stale fingerprints before mutation.
- Use the existing `NullApprovedSnapshotWorkspace` and `NullCandidateWorkspace` as sociable in-memory fakes; do not introduce mocks, a generic framework, an apply command, catalog changes, activation, approval, build, or deployment.
- Do not commit without an explicit operator request; use `git diff --check` as the task checkpoint.

---

## File Structure

- Create `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationDecisionSet.java`: immutable executable-carrier value with its declared minimal schema.
- Create `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionCodec.java`: the only JSON reader/writer for draft and human-decision carriers.
- Create `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionException.java`: named fail-closed validation failure.
- Create `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionValidator.java`: re-inspects inventory and compares the carrier fingerprint.
- Modify `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java`: adds mutually exclusive `--draft` and `--validate` read-only modes.
- Create `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionCodecTest.java`: JSON boundary and deterministic-draft tests.
- Create `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionValidatorTest.java`: in-memory fresh/stale validation tests.
- Modify `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/LegacyInventoryCliAcceptanceTest.java`: end-to-end command and no-review-mutation tests.

### Task 1: Strict decision-carrier codec

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/MigrationDecisionSet.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionCodec.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionException.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionCodecTest.java`

**Interfaces:**
- Produces `record MigrationDecisionSet(int schemaVersion, String inventorySha256)`.
- Produces `String LegacyMigrationDecisionCodec.draftFor(LegacyWorkspaceInventory inventory)`.
- Produces `MigrationDecisionSet LegacyMigrationDecisionCodec.decisionsFrom(String json)`.
- Produces `LegacyMigrationDecisionException extends RuntimeException` for invalid external decision JSON.

- [ ] **Step 1: Write the failing codec tests**

```java
@Test
void draftIsDeterministicAndPermanentlyNonExecutable() {
    LegacyWorkspaceInventory inventory = new LegacyWorkspaceInventory(
            List.of(), List.of(), List.of(), List.of(), "a".repeat(64));

    String draft = new LegacyMigrationDecisionCodec().draftFor(inventory);
    JsonNode root = new ObjectMapper().readTree(draft);

    assertTrue(root.get("draftOnly").asBoolean());
    assertEquals("human-resolution-required", root.get("status").asText());
    assertThrows(LegacyMigrationDecisionException.class,
            () -> new LegacyMigrationDecisionCodec().decisionsFrom(draft));
}

@Test
void decisionReaderRejectsDuplicateUnknownMissingAndMalformedFields() {
    LegacyMigrationDecisionCodec codec = new LegacyMigrationDecisionCodec();
    String draftMarked = "{\"schemaVersion\":1,\"inventorySha256\":\""
            + "a".repeat(64) + "\",\"draftOnly\":true}";

    assertThrows(LegacyMigrationDecisionException.class,
            () -> codec.decisionsFrom("{\"schemaVersion\":1,\"schemaVersion\":1,\"inventorySha256\":\"a\"}"));
    assertThrows(LegacyMigrationDecisionException.class,
            () -> codec.decisionsFrom(draftMarked));
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml -Dtest=LegacyMigrationDecisionCodecTest test`

Expected: FAIL because the S22 value and codec classes do not exist.

- [ ] **Step 3: Implement the smallest immutable carrier and codec**

```java
public record MigrationDecisionSet(int schemaVersion, String inventorySha256) {
    public MigrationDecisionSet {
        if (schemaVersion != 1 || !inventorySha256.matches("[0-9a-f]{64}")) {
            throw new LegacyMigrationDecisionException("Decision file has an invalid schema or inventory fingerprint.");
        }
    }
}

public final class LegacyMigrationDecisionCodec {
    public String draftFor(LegacyWorkspaceInventory inventory) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("schemaVersion", 1);
        draft.put("draftOnly", true);
        draft.put("status", "human-resolution-required");
        draft.set("inventory", mapper.valueToTree(inventory));
        draft.set("decisionTemplate", mapper.valueToTree(new MigrationDecisionSet(1, inventory.inventorySha256())));
        return write(draft);
    }

    public MigrationDecisionSet decisionsFrom(String json) {
        JsonNode root = executableDecisionRoot(json);
        return new MigrationDecisionSet(requiredSchemaVersion(root), requiredFingerprint(root));
    }
}
```

Use one `ObjectMapper` configured with `JsonParser.Feature.STRICT_DUPLICATE_DETECTION`, explicit object/field/type checks, and `List.copyOf`-style immutable ownership already used by inventory. Keep constructors code-free and split parsing into intention-revealing private methods such as `executableDecisionRoot`, `requiredFingerprint`, and `rejectDraftMarker`; the public methods are composed-method entry points. The draft's `decisionTemplate` is exactly `{ "schemaVersion": 1, "inventorySha256": "<inventory hash>" }`; it contains no future S23 resolution vocabulary.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `mvn -f publication-exporter/pom.xml -Dtest=LegacyMigrationDecisionCodecTest test`

Expected: PASS; duplicate, wrong-shape, marker-bearing, malformed, and missing-field JSON all fail closed.

- [ ] **Step 5: Check the task diff**

Run: `git diff --check -- publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy`

Expected: no whitespace errors. Do not commit without explicit operator authorization.

### Task 2: Freshness validator over real in-memory collaboration

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionValidator.java`
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy/LegacyMigrationDecisionValidatorTest.java`

**Interfaces:**
- Consumes `LegacyWorkspaceInventoryHandler`, `LegacyMigrationDecisionCodec`, and `MigrationDecisionSet` from Task 1.
- Produces `MigrationDecisionSet LegacyMigrationDecisionValidator.validate(String decisionJson)`.

- [ ] **Step 1: Write failing in-memory acceptance tests**

```java
@Test
void validatesSeparateHumanDecisionBoundToCurrentInventory() {
    LegacyWorkspaceInventoryHandler inventory = inventoryWith(approvedIdentity("essay"));
    String decisionJson = new ObjectMapper().writeValueAsString(Map.of(
            "schemaVersion", 1, "inventorySha256", inventory.inspect().inventorySha256()));

    MigrationDecisionSet decision = new LegacyMigrationDecisionValidator(
            inventory, new LegacyMigrationDecisionCodec()).validate(decisionJson);

    assertEquals(inventory.inspect().inventorySha256(), decision.inventorySha256());
}

@Test
void rejectsHumanDecisionWhenWorkspaceChangesAfterItsFingerprintWasCaptured() {
    NullApprovedSnapshotWorkspace approved = approvedWorkspaceWith("essay");
    LegacyWorkspaceInventoryHandler inventory = new LegacyWorkspaceInventoryHandler(approved, new NullCandidateWorkspace());
    String oldDecision = decisionFor(inventory.inspect().inventorySha256());
    approved.install(IDENTITY_2, snapshotWithSourceId("source-2"));

    assertThrows(LegacyMigrationDecisionException.class,
            () -> new LegacyMigrationDecisionValidator(inventory, new LegacyMigrationDecisionCodec()).validate(oldDecision));
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml -Dtest=LegacyMigrationDecisionValidatorTest test`

Expected: FAIL because no validator re-inspects inventory and compares fingerprints.

- [ ] **Step 3: Implement freshness validation without an I/O seam above the workspaces**

```java
public final class LegacyMigrationDecisionValidator {
    private final LegacyWorkspaceInventoryHandler inventory;
    private final LegacyMigrationDecisionCodec codec;

    public MigrationDecisionSet validate(String decisionJson) {
        MigrationDecisionSet decision = codec.decisionsFrom(decisionJson);
        rejectStaleFingerprint(decision, inventory.inspect());
        return decision;
    }
}
```

`rejectStaleFingerprint` compares `decision.inventorySha256()` with the newly inspected `LegacyWorkspaceInventory.inventorySha256()` and throws the named exception with fresh-inventory guidance. Do not cache a fingerprint, mutate either null workspace, or add a mock. The existing null workspaces are the lowest controlled state edge, so all S22 mapping and validation run as production code in the test.

- [ ] **Step 4: Run focused legacy tests to verify they pass**

Run: `mvn -f publication-exporter/pom.xml -Dtest=LegacyMigrationDecisionCodecTest,LegacyMigrationDecisionValidatorTest,LegacyWorkspaceInventoryHandlerTest test`

Expected: PASS; a current separate file validates, a generated draft and stale file fail, and S21 inventory remains deterministic.

- [ ] **Step 5: Check the task diff**

Run: `git diff --check -- publication-exporter/src/main/java/dev/eugene/publicationexporter/legacy publication-exporter/src/test/java/dev/eugene/publicationexporter/legacy`

Expected: no whitespace errors. Do not commit without explicit operator authorization.

### Task 3: Read-only CLI draft and validation modes

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/LegacyInventoryCommand.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/LegacyInventoryCliAcceptanceTest.java`

**Interfaces:**
- Consumes `LegacyMigrationDecisionCodec.draftFor(LegacyWorkspaceInventory)` and `LegacyMigrationDecisionValidator.validate(String)`.
- Adds `legacy-inventory --review <review-root> --draft <outside-review-path>`.
- Adds `legacy-inventory --review <review-root> --validate <human-decision-path>`.
- Keeps `legacy-inventory --review <review-root>` as the existing inventory-only JSON response.

- [ ] **Step 1: Write failing CLI acceptance tests**

```java
@Test
void draftModeWritesOnlyOutsideReviewRootAndMarksTheFileNonExecutable() throws Exception {
    Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
    byte[] before = reviewTreeBytes(reviewDirectory);

    int exitCode = new CommandLine(new Main()).execute(
            "legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());

    assertEquals(0, exitCode);
    assertTrue(new ObjectMapper().readTree(Files.readString(draft)).get("draftOnly").asBoolean());
    assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
}

@Test
void validationRejectsDraftAndStaleHumanFileWithoutReviewMutation() throws Exception {
    Path draft = reviewDirectory.getParent().resolve("migration-draft.json");
    execute("legacy-inventory", "--review", reviewDirectory.toString(), "--draft", draft.toString());
    byte[] before = reviewTreeBytes(reviewDirectory);

    assertNotEquals(0, execute("legacy-inventory", "--review", reviewDirectory.toString(), "--validate", draft.toString()));
    assertArrayEquals(before, reviewTreeBytes(reviewDirectory));
}
```

Add a separate success test that creates the distinct two-field human decision file from the draft template, validates it with exit code zero, then changes the review fixture and asserts the same file exits non-zero. The fixture helper must serialize file-tree paths and bytes in stable order so the negative mutation assertion is semantic rather than substring-based.

- [ ] **Step 2: Run the focused CLI test to verify it fails**

Run: `mvn -f publication-exporter/pom.xml -Dtest=LegacyInventoryCliAcceptanceTest test`

Expected: FAIL because the command has neither `--draft` nor `--validate`.

- [ ] **Step 3: Wire mutually exclusive read-only modes**

```java
@Option(names = "--draft")
Path draftFile;

@Option(names = "--validate")
Path decisionFile;

public Integer call() throws Exception {
    LegacyWorkspaceInventoryHandler inventory = inventoryHandler();
    if (draftFile != null) {
        writeDraftOutsideReviewRoot(inventory.inspect());
        return 0;
    }
    if (decisionFile != null) {
        validateHumanDecision(inventory);
        return 0;
    }
    printInventory(inventory.inspect());
    return 0;
}
```

Use Picocli’s exclusive option group (or an equivalent explicit single guard that rejects both options) before any filesystem write. `writeDraftOutsideReviewRoot` resolves and normalizes both paths, rejects a destination under the normalized review root, creates only the draft parent directory, and writes UTF-8 draft JSON. `validateHumanDecision` only reads the external file, invokes the validator, and prints a deterministic success JSON/object; failures propagate to Picocli as non-zero without writing review state. Keep public `call()` as a table of contents; put path guarding, construction, output, and validation in named private methods.

- [ ] **Step 4: Run focused CLI and legacy tests**

Run: `mvn -f publication-exporter/pom.xml -Dtest=LegacyInventoryCliAcceptanceTest,LegacyMigrationDecisionCodecTest,LegacyMigrationDecisionValidatorTest,LegacyWorkspaceInventoryHandlerTest test`

Expected: PASS; normal inventory output is unchanged, draft output is separate/non-executable, fresh human file succeeds, stale/draft files fail, and review-tree bytes do not change.

- [ ] **Step 5: Run the full module regression suite and check the diff**

Run: `mvn -f publication-exporter/pom.xml test && git diff --check`

Expected: the full `publication-exporter` suite passes and the working tree has no whitespace errors. Do not commit without explicit operator authorization.

## Plan Self-Review

- Spec coverage: Task 1 makes deterministic, visibly non-executable drafts and rejects malformed/marker-bearing JSON; Task 2 proves fresh and stale fingerprint behavior with in-memory workspaces; Task 3 proves the CLI’s read-only boundary and no review-workspace mutation.
- Placeholder scan: no implementation item delegates validation, error behavior, path confinement, or test cases to unspecified future work. S23 resolution vocabulary is expressly excluded rather than deferred inside a task.
- Type consistency: every later task uses `MigrationDecisionSet`, `LegacyMigrationDecisionCodec`, and `LegacyMigrationDecisionValidator.validate(String)` exactly as Task 1/2 define them.
