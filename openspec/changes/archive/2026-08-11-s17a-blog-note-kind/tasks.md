<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q -o test` from publication-exporter/.
- Outside-in TDD: sections 1-2 are a behaviour-preserving refactor (essay-only) — the existing acceptance suite is
  the safety net, no new test is required for those sections, but `mvn -q -o test` MUST stay green after each step.
  Section 3 adds a genuinely new failing acceptance test (blog/note) before any note-admitting production code —
  standard outside-in discipline (openspec/implementation-plan.md).
- Zero new production boundary adapters this slice. Kind selection is pure in-process dispatch.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- Never touch book/album/concept/curated_page — out of scope until their own slices.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: every new value type follows this
  project's existing convention exactly (see `Diagnostic`, `PublicationIdentity`, `KindContract`) — public final
  class, private all-args constructor, named static factories (SBPP-BEH-02 Constructor Method), no getter-prefixed
  accessors (Elegant Objects 3.5), `equals`/`hashCode`/`toString` on every value object, immutable `List.copyOf(...)`
  fields. `PublicationKind` is a role (interface) each kind implements directly — no reflection, no generic
  rule-table engine (Elegant Objects: prefer polymorphism over type-switches). No comments in production code
  beyond what non-obvious rationale demands — this file's own comments are plan scaffolding, not a model for the
  code you write.
- GraalVM reflect-config.json: NOT required this slice. No new Jackson-serialized DTO type is introduced —
  `KindContract`/`FieldContract`/`PublicationIdentity`/`Diagnostic` already exist and are already registered
  (from S15). `AdmittedPublication` and `PublicationKind` are internal, never Jackson-serialized directly. Confirm
  this by inspecting `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json` before
  closing this slice — if you introduce any new class annotated `@JsonProperty`, add it there (S15's final review
  lesson: this exact gap is invisible to the JVM test suite and only breaks the native build).
- Full reference documents (read before starting): proposal.md, specs/publication-admission/spec.md,
  specs/public-content-model/spec.md, design.md — all in
  openspec/changes/s17a-blog-note-kind/. design.md's Decisions D1-D6 map directly onto the classes this file
  creates; read it first if anything below is unclear on *why*, not just *what*.
-->

## 1. Extract the PublicationKind seam (essay-only, behaviour-preserving)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKind.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/AdmittedPublication.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayPublicationKind.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java`
- Delete: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/EssayAdmission.java`
- Delete: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/EssayPublicationContract.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/PublicationContractWriter.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java`
- Modify (move+adapt): `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionTest.java` → split (see 1.7)
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/EssayAdmissionFixtures.java` → rename/adapt (see 1.7)
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java`

**Design context (design.md D1, D2, D4, D5):** `PublicationKind` is a role each kind implements directly.
`AdmittedPublication` replaces `EssayAdmission.Result` as the kind-neutral admission carrier. Field-level
validation (public ID slug, non-blank `id`/`title`/`description`) moves onto each kind's `admit(...)`. The shared,
kind-independent checks — `publish` must be true, and resolving which kind a `(publicCollection, publicContentType)`
pair names — move to `NoteIntake`, matching the plan's own text: "`PublicationKinds` owns deterministic lookup,
unsupported-kind diagnostics, and sorted contract enumeration."

- [x] 1.1 Create `PublicationKind`:

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;

public interface PublicationKind {

    String collection();

    String contentType();

    String routePrefix();

    AdmittedPublication admit(MarkdownNote frontmatter);

    KindContract contract();
}
```

- [x] 1.2 Create `AdmittedPublication` (same accepted/blocked shape as today's `EssayAdmission.Result`, plus a `kind()` accessor):

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;

public final class AdmittedPublication {

    private final PublicationKind kind;
    private final PublicationIdentity identity;
    private final String sourceId;
    private final String title;
    private final String description;
    private final List<Diagnostic> diagnostics;

    private AdmittedPublication(PublicationKind kind, PublicationIdentity identity, String sourceId,
            String title, String description, List<Diagnostic> diagnostics) {
        this.kind = kind;
        this.identity = identity;
        this.sourceId = sourceId;
        this.title = title;
        this.description = description;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static AdmittedPublication accepted(
            PublicationKind kind, PublicationIdentity identity, String sourceId, String title, String description) {
        return new AdmittedPublication(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(sourceId, "sourceId"),
                Objects.requireNonNull(title, "title"),
                Objects.requireNonNull(description, "description"),
                List.of());
    }

    public static AdmittedPublication blocked(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("blocked() requires at least one diagnostic");
        }
        return new AdmittedPublication(null, null, null, null, null, diagnostics);
    }

    public boolean accepted() {
        return diagnostics.isEmpty();
    }

    public PublicationKind kind() {
        return kind;
    }

    public PublicationIdentity identity() {
        return identity;
    }

    public String sourceId() {
        return sourceId;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AdmittedPublication that)) {
            return false;
        }
        return Objects.equals(kind, that.kind) && Objects.equals(identity, that.identity)
                && Objects.equals(sourceId, that.sourceId) && Objects.equals(title, that.title)
                && Objects.equals(description, that.description) && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, identity, sourceId, title, description, diagnostics);
    }

    @Override
    public String toString() {
        return "AdmittedPublication[kind=" + kind + ", identity=" + identity + ", sourceId=" + sourceId
                + ", title=" + title + ", description=" + description + ", diagnostics=" + diagnostics + "]";
    }
}
```

- [x] 1.3 Create `EssayPublicationKind` — behaviour-preserving move of `EssayAdmission`'s field-level rules only
      (publish/collection/contentType checks move to `NoteIntake` in task 2, so they are NOT here):

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class EssayPublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"));

    @Override
    public String collection() {
        return "blog";
    }

    @Override
    public String contentType() {
        return "essay";
    }

    @Override
    public String routePrefix() {
        return "essays";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        return AdmittedPublication.accepted(
                this, PublicationIdentity.of(collection(), contentType(), publicId), sourceId, title, description);
    }

    @Override
    public KindContract contract() {
        List<FieldContract> requiredFields = new ArrayList<>();
        requiredFields.add(FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"));
        requiredFields.add(FieldContract.allowedValue("publicCollection", FieldContract.Type.STRING, collection()));
        requiredFields.add(FieldContract.allowedValue("publicContentType", FieldContract.Type.STRING, contentType()));
        for (FieldRule rule : FIELD_RULES) {
            requiredFields.add(toFieldContract(rule));
        }
        return KindContract.of(collection(), contentType(), requiredFields, List.of());
    }

    private String requireValidPublicId(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        String publicId = frontmatter.string("publicId").filter(this::isSlug).orElse(null);
        if (publicId == null) {
            diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
        }
        return publicId;
    }

    private boolean isSlug(String candidate) {
        return PUBLIC_ID_SLUG.matcher(candidate).matches();
    }

    private String requireNonBlank(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        String value = frontmatter.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
        if (value == null) {
            diagnostics.add(Diagnostic.blocking(key, "Note has no " + key + "."));
        }
        return value;
    }

    private static FieldContract toFieldContract(FieldRule rule) {
        return switch (rule.kind()) {
            case MUST_EQUAL ->
                    FieldContract.allowedValue(rule.field(), FieldContract.Type.STRING, rule.literalValue());
            case MUST_MATCH -> FieldContract.matchingPattern(rule.field(), rule.pattern().pattern());
            case NON_BLANK -> FieldContract.nonBlank(rule.field());
        };
    }
}
```

Note: `FieldRule` (admission/FieldRule.java) is unchanged and reused as-is — it is already kind-neutral (field
name + rule kind + literal/pattern), not essay-specific. Do not modify it.

- [x] 1.4 Create `PublicationKinds` (still composing only `EssayPublicationKind` in this task — `NotePublicationKind`
      is added in task 4):

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.contract.KindContract;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PublicationKinds {

    private final List<PublicationKind> kinds;

    private PublicationKinds(List<PublicationKind> kinds) {
        this.kinds = List.copyOf(kinds);
    }

    public static PublicationKinds installed() {
        return new PublicationKinds(List.of(new EssayPublicationKind()));
    }

    public Optional<PublicationKind> forIdentity(String collection, String contentType) {
        return kinds.stream()
                .filter(kind -> kind.collection().equals(collection) && kind.contentType().equals(contentType))
                .findFirst();
    }

    public List<KindContract> sortedContracts() {
        return kinds.stream()
                .map(PublicationKind::contract)
                .sorted(Comparator.comparing(KindContract::collection).thenComparing(KindContract::contentType))
                .toList();
    }
}
```

- [x] 1.5 Delete `EssayAdmission.java` and `EssayPublicationContract.java`.

- [x] 1.6 Update `PublicationContractWriter` to compose `PublicationKinds` instead of the hardcoded single-kind list:

```java
package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.PublicationKinds;

public final class PublicationContractWriter {

    public PublicationContract write() {
        return PublicationContract.of(1, PublicationKinds.installed().sortedContracts());
    }
}
```

- [x] 1.7 Update `NoteIntake` to own the shared `publish` check and kind dispatch, delegating field-level
      validation to the resolved kind, and take `PublicationKinds` as a constructor argument (design.md D3):

```java
package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.admission.AdmittedPublication;
import dev.eugene.publicationexporter.admission.PublicationKind;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class NoteIntake {

    private final PublicationKinds publicationKinds;

    public NoteIntake(PublicationKinds publicationKinds) {
        this.publicationKinds = Objects.requireNonNull(publicationKinds, "publicationKinds");
    }

    public Result admit(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note path escapes the vault root.")));
        }
        if (!notePath.hasMarkdownExtension()) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note path must name a Markdown file.")));
        }
        if (!vaultReader.exists(notePath)) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
        return admitExistingNote(notePath, vaultReader);
    }

    private Result admitExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        try {
            String source = vaultReader.readSource(notePath);
            MarkdownNote frontmatter = MarkdownNote.parse(source);
            String sourceHash = ContentHash.sha256Hex(source);
            AdmittedPublication admission = admitAgainstKind(frontmatter);
            if (!admission.accepted()) {
                return Result.blocked(admission.diagnostics());
            }
            return Result.accepted(admission, frontmatter, sourceHash);
        } catch (NoSuchElementException | UncheckedIOException failure) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
    }

    private AdmittedPublication admitAgainstKind(MarkdownNote frontmatter) {
        if (!frontmatter.flag("publish")) {
            return AdmittedPublication.blocked(List.of(
                    Diagnostic.blocking("publish", "must be true; allowed value: true")));
        }
        String collection = frontmatter.string("publicCollection").orElse("");
        String contentType = frontmatter.string("publicContentType").orElse("");
        Optional<PublicationKind> kind = publicationKinds.forIdentity(collection, contentType);
        if (kind.isEmpty()) {
            return AdmittedPublication.blocked(List.of(Diagnostic.blocking(
                    "publicContentType", "publicCollection/publicContentType is not a supported publication kind")));
        }
        return kind.get().admit(frontmatter);
    }

    public static final class Result {

        private final AdmittedPublication admission;
        private final MarkdownNote frontmatter;
        private final String sourceHash;
        private final List<Diagnostic> diagnostics;

        private Result(AdmittedPublication admission, MarkdownNote frontmatter,
                String sourceHash, List<Diagnostic> diagnostics) {
            this.admission = admission;
            this.frontmatter = frontmatter;
            this.sourceHash = sourceHash;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(AdmittedPublication admission, MarkdownNote frontmatter, String sourceHash) {
            return new Result(
                    Objects.requireNonNull(admission, "admission"),
                    Objects.requireNonNull(frontmatter, "frontmatter"),
                    Objects.requireNonNull(sourceHash, "sourceHash"),
                    List.of());
        }

        static Result blocked(List<Diagnostic> diagnostics) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("blocked() requires at least one diagnostic");
            }
            return new Result(null, null, null, diagnostics);
        }

        public boolean accepted() {
            return diagnostics.isEmpty();
        }

        public PublicationKind kind() {
            return admission.kind();
        }

        public PublicationIdentity identity() {
            return admission.identity();
        }

        public String body() {
            return frontmatter.body();
        }

        public String sourceHash() {
            return sourceHash;
        }

        public Optional<String> frontmatterString(String key) {
            return frontmatter.string(Objects.requireNonNull(key, "key"));
        }

        public String title() {
            return admission.title();
        }

        public String description() {
            return admission.description();
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
```

- [x] 1.8 Split the old `EssayAdmissionTest`/`EssayAdmissionFixtures` by which layer now owns each case:

  **Dispatch-level cases move to `NoteIntakeTest`** (unpublished, wrong collection, wrong content type — these no
  longer reach a specific kind's `admit()`, `NoteIntake` blocks them before dispatch). Update
  `publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java`: change
  `private final NoteIntake intake = new NoteIntake();` to
  `private final NoteIntake intake = new NoteIntake(dev.eugene.publicationexporter.admission.PublicationKinds.installed());`
  (add a proper import instead of the fully-qualified reference), then add:

```java
    @Test
    void unpublishedEssayIsBlocked() {
        String unpublished = VALID_ESSAY.replace("publish: true\n", "");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, unpublished)));

        assertFalse(result.accepted());
        assertEquals("publish", result.diagnostics().get(0).field());
    }

    @Test
    void unsupportedCollectionContentTypePairIsBlocked() {
        String wrongCollection = VALID_ESSAY.replace("publicCollection: blog", "publicCollection: bibliography");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, wrongCollection)));

        assertFalse(result.accepted());
        assertEquals("publicContentType", result.diagnostics().get(0).field());
    }

    @Test
    void unsupportedContentTypeIsBlocked() {
        String wrongContentType = VALID_ESSAY.replace("publicContentType: essay", "publicContentType: claim");
        VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
        NoteIntake.Result result = intake.admit(path, VaultReader.createNull(Map.of(path, wrongContentType)));

        assertFalse(result.accepted());
        assertEquals("publicContentType", result.diagnostics().get(0).field());
    }
```

  (`VALID_ESSAY`'s frontmatter uses YAML `key: value\n` lines — the `.replace("publish: true\n", "")` removes the
  whole line, leaving `publish` absent, which `MarkdownNote.flag("publish")` treats as `false`.)

  **Kind-level cases (publicId, id, title, description) rename in place**, since `EssayPublicationKind.admit(...)`
  keeps identical logic to the old `EssayAdmission.admit(...)` minus the removed publish/collection/contentType
  checks:
  - Rename `EssayAdmissionFixture.java` → `EssayPublicationKindFixture.java` (same content, class renamed).
  - Rename `EssayAdmissionFixtures.java` → `EssayPublicationKindFixtures.java`, **remove** the `unpublished`,
    `wrongCollection`, `wrongContentType` fixtures (now covered above), keep the rest
    (`validEssay`, `invalidPublicId`, `missingSourceId`, `blankSourceId`, `nullSourceId`, `missingTitle`,
    `blankDescription`, `missingPublicIdAndSourceId`) unchanged.
  - Rename `EssayAdmissionTest.java` → `EssayPublicationKindTest.java`:
    `private final EssayPublicationKind admission = new EssayPublicationKind();`, update the `@MethodSource` to
    `"dev.eugene.publicationexporter.admission.EssayPublicationKindFixtures#all"`, change
    `EssayAdmission.Result result = admission.admit(frontmatter);` to
    `AdmittedPublication result = admission.admit(frontmatter);`, and change the return type in
    `blockedFields(...)` from `EssayAdmission.Result` to `AdmittedPublication`. The `sourceId()` accessor moved
    from `EssayAdmission.Result` to `AdmittedPublication` unchanged — `validEssayResultCarriesIdentityAndFields`
    needs no assertion changes beyond the type rename.

- [x] 1.9 Run `mvn -q -o test` from `publication-exporter/`. Expected: PASS, full suite green (this is a
      behaviour-preserving refactor — no acceptance test changed its expected outcome, only which class produces
      which diagnostic for the three moved cases).

- [x] 1.10 Commit:

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/admission publication-exporter/src/main/java/dev/eugene/publicationexporter/contract publication-exporter/src/main/java/dev/eugene/publicationexporter/intake/NoteIntake.java publication-exporter/src/test/java/dev/eugene/publicationexporter/admission publication-exporter/src/test/java/dev/eugene/publicationexporter/intake/NoteIntakeTest.java
git commit -m "refactor(exporter): extract PublicationKind/PublicationKinds seam from EssayAdmission (essay-only, behaviour-preserving)"
```

## 2. Thread PublicationKinds/NoteIntake through call sites (behaviour-preserving)

**Files:**
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/inspect/InspectPublicationHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/refresh/RefreshPublicationQueueHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/manifest/PublicationManifestHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/markreviewed/MarkReviewedHandler.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/PrepareCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/InspectPublicationCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/RefreshPublicationQueueCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/WritePublicationManifestCommand.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/cli/MarkReviewedCommand.java`
- Modify (test wiring only): `PrepareHandlerTest.java`, `InspectPublicationHandlerTest.java`,
  `RefreshPublicationQueueHandlerTest.java`, `PublicationManifestHandlerTest.java`, `MarkReviewedHandlerTest.java`,
  `LinkResolverTest.java`, `PublicNoteIndex`-constructing test helpers, `AstroBuildSmokeIT.java` if it constructs
  a handler directly

**Design context (design.md D3):** every one of these 6 handler classes currently `new NoteIntake()`s inline —
the one collaborator not already constructor-injected, unlike `TranslationWorker`/`CandidateWorkspace`. Add
`NoteIntake` as a constructor parameter to each, matching the existing pattern. Each Picocli `*Command` becomes
the composition root: it constructs one `PublicationKinds.installed()` → `new NoteIntake(kinds)` per run and
passes it to whichever handler(s) it uses.

- [x] 2.1 `PrepareHandler`: add `NoteIntake noteIntake` as the **first** constructor parameter (mirroring the
      existing collaborator order), store it, and replace both internal `new NoteIntake().admit(...)` call sites
      (the `intake` field-name is fine to keep as a local variable name):

```java
    private final NoteIntake noteIntake;
    private final TranslationWorker translationWorker;
    private final CandidateWorkspace candidateWorkspace;
    private final ApprovedSnapshotWorkspace approvedSnapshotWorkspace;
    private final WorkflowStatusEditor workflowStatusEditor;

    public PrepareHandler(NoteIntake noteIntake, TranslationWorker translationWorker,
            CandidateWorkspace candidateWorkspace, ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
            WorkflowStatusEditor workflowStatusEditor) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
        this.translationWorker = Objects.requireNonNull(translationWorker, "translationWorker");
        this.candidateWorkspace = Objects.requireNonNull(candidateWorkspace, "candidateWorkspace");
        this.approvedSnapshotWorkspace =
                Objects.requireNonNull(approvedSnapshotWorkspace, "approvedSnapshotWorkspace");
        this.workflowStatusEditor = Objects.requireNonNull(workflowStatusEditor, "workflowStatusEditor");
    }
```

  In `prepare(...)` (was line 59): `NoteIntake.Result intake = noteIntake.admit(notePath, vaultReader);`

  Also in `prepare(...)`, the `PublicNoteIndex.from(vaultReader)` call (was line 65) becomes
  `PublicNoteIndex.from(vaultReader, noteIntake);` (see 2.2 for `PublicNoteIndex`'s new signature).

  The static method `sourceFreshness(...)` (was lines 257-275) currently does `new NoteIntake().admit(...)` at
  its own line 261 — it is `private static`, so it cannot read the instance field. Add `NoteIntake noteIntake` as
  a new parameter to `sourceFreshness(...)` (same threading style already used there for `knownNotes` and
  `vaultAssetReader`), update its one caller within the class to pass `this.noteIntake`, and change its body's
  `NoteIntake.Result current = new NoteIntake().admit(notePath, vaultReader);` to
  `NoteIntake.Result current = noteIntake.admit(notePath, vaultReader);`.

- [x] 2.2 `PublicNoteIndex`: take `NoteIntake` instead of constructing its own, full replacement:

```java
package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PublicNoteIndex {

    private final Map<String, String> routesByFilenameStem;

    PublicNoteIndex(Map<String, String> routesByFilenameStem) {
        this.routesByFilenameStem = Map.copyOf(Objects.requireNonNull(routesByFilenameStem, "routesByFilenameStem"));
    }

    static PublicNoteIndex from(VaultReader vaultReader, NoteIntake noteIntake) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Objects.requireNonNull(noteIntake, "noteIntake");
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
            registerIfAdmitted(vaultReader, candidate, noteIntake, routes, ambiguousStems);
        }
        ambiguousStems.forEach(routes::remove);
        return new PublicNoteIndex(routes);
    }

    Optional<String> routeFor(String linkTarget) {
        return Optional.ofNullable(routesByFilenameStem.get(linkTarget));
    }

    private static void registerIfAdmitted(
            VaultReader vaultReader, VaultRelativePath candidate, NoteIntake noteIntake,
            Map<String, String> routes, Set<String> ambiguousStems) {
        NoteIntake.Result intake = noteIntake.admit(candidate, vaultReader);
        if (!intake.accepted()) {
            return;
        }
        String stem = filenameStem(candidate);
        if (routes.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        routes.put(stem, "/" + intake.kind().routePrefix() + "/" + intake.identity().publicId() + "/");
    }

    private static String filenameStem(VaultRelativePath path) {
        String value = path.value();
        int lastSlash = value.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
```

  Note: the private static `routeFor(PublicationIdentity)` helper is gone — route computation is now inline using
  `intake.kind().routePrefix()`, which is where design.md D6 plugs in (`EssayPublicationKind.routePrefix()` returns
  `"essays"`, so existing essay-to-essay link tests keep resolving to `/essays/...` unchanged).

- [x] 2.3 `InspectPublicationHandler`: add `NoteIntake noteIntake` as the first constructor parameter, store it,
      replace `new NoteIntake().admit(...)` (was line 44) with `noteIntake.admit(...)`.

- [x] 2.4 `RefreshPublicationQueueHandler`: add `NoteIntake noteIntake` as the first constructor parameter, store
      it, replace `new NoteIntake().admit(...)` (was line 54) with `noteIntake.admit(...)`.

- [x] 2.5 `PublicationManifestHandler`: add a constructor taking `NoteIntake noteIntake` (this class previously had
      no constructor at all — it only had the default one), store it, replace `new NoteIntake().admit(...)` (was
      line 23) with `noteIntake.admit(...)`:

```java
package dev.eugene.publicationexporter.manifest;

import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PublicationManifestHandler {

    private final NoteIntake noteIntake;

    public PublicationManifestHandler(NoteIntake noteIntake) {
        this.noteIntake = Objects.requireNonNull(noteIntake, "noteIntake");
    }

    public PublicationManifest manifest(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        List<ManifestEntry> entries = new ArrayList<>();
        for (VaultRelativePath path : vaultReader.listPublishCandidates()) {
            entries.add(entryFor(path, vaultReader));
        }
        return PublicationManifest.of(entries);
    }

    private ManifestEntry entryFor(VaultRelativePath path, VaultReader vaultReader) {
        NoteIntake.Result intake = noteIntake.admit(path, vaultReader);
        return intake.accepted()
                ? ManifestEntry.admitted(path.value(), intake.identity())
                : ManifestEntry.blocked(path.value(), intake.diagnostics());
    }
}
```

- [x] 2.6 `MarkReviewedHandler`: add `NoteIntake noteIntake` as the first constructor parameter, store it, replace
      both `new NoteIntake().admit(...)` call sites (was lines 50 and 84) with `noteIntake.admit(...)`.

- [x] 2.7 Update each `*Command`'s `call()` to construct `PublicationKinds.installed()` and `new NoteIntake(kinds)`
      once, passing it into its handler(s):

  `PrepareCommand.java` (add imports `dev.eugene.publicationexporter.admission.PublicationKinds` and
  `dev.eugene.publicationexporter.intake.NoteIntake`):

```java
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        BridgeResponse response = new PrepareHandler(
                noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor)
                .prepare(VaultRelativePath.of(notePath), vaultReader, vaultAssetReader);
```

  `InspectPublicationCommand.java` (add the same two imports):

```java
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        BridgeResponse response = new InspectPublicationHandler(noteIntake, candidateWorkspace, approvedSnapshotWorkspace)
                .inspect(VaultRelativePath.of(notePath), vaultReader);
```

  (this also means `InspectPublicationHandler`'s constructor parameter order in task 2.3 must put `noteIntake`
  first, ahead of `candidateWorkspace`/`approvedSnapshotWorkspace` — match this call.)

  `RefreshPublicationQueueCommand.java`:

```java
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        BridgeResponse response = new RefreshPublicationQueueHandler(
                        noteIntake, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor)
                .refresh(vaultReader);
```

  `WritePublicationManifestCommand.java`:

```java
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        PublicationManifest manifest = new PublicationManifestHandler(noteIntake).manifest(vaultReader);
```

  `MarkReviewedCommand.java`:

```java
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        BridgeResponse response = new MarkReviewedHandler(
                noteIntake, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor)
                .markReviewed(VaultRelativePath.of(notePath), vaultReader);
```

  `WritePublicationContractCommand.java`: **no change** — `PublicationContractWriter` already builds its own
  `PublicationKinds.installed()` internally (task 1.6).

- [x] 2.8 Update every test that constructs one of the 6 touched handlers (or `PublicNoteIndex`) directly to pass
      `new NoteIntake(PublicationKinds.installed())` (or, for tests using `VaultReader.createNull(...)` fixtures
      exclusively, the same real `PublicationKinds.installed()` — there is no fake `PublicationKinds` needed since
      it is a pure in-memory value object, not an I/O boundary). This includes at minimum: `PrepareHandlerTest`,
      `InspectPublicationHandlerTest`, `RefreshPublicationQueueHandlerTest`, `PublicationManifestHandlerTest`,
      `MarkReviewedHandlerTest`, `LinkResolverTest` (constructs `PublicNoteIndex` directly with a `Map` — that
      constructor is unchanged, only `PublicNoteIndex.from(...)` gained the new parameter, so check whether
      `LinkResolverTest` also calls `.from(...)` anywhere). Grep for `new PrepareHandler(`, `new
      InspectPublicationHandler(`, `new RefreshPublicationQueueHandler(`, `new PublicationManifestHandler(`, `new
      MarkReviewedHandler(`, `PublicNoteIndex.from(` across `src/test/java` to find every site.

- [x] 2.9 Run `mvn -q -o test` from `publication-exporter/`. Expected: PASS, full suite green — this task is
      wiring-only, no behaviour changed for essay.

- [x] 2.10 Commit:

```bash
git add publication-exporter/src
git commit -m "refactor(exporter): thread NoteIntake/PublicationKinds through handler and command call sites"
```

## 3. Failing acceptance test: blog/note completes prepare → approve → release (RED)

**Files:**
- Create or extend: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
  (or a new `BlogNoteAcceptanceTest.java` alongside it if `PrepareHandlerTest` is already large — check its current
  line count first; prefer extending it if under ~600 lines, matching this project's existing file-size norms)

This is the fast, in-memory acceptance test the outside-in discipline calls for. It exercises the full path
already proven for essay — prepare (admission + translation + candidate install), approve (install as approved
snapshot) — through `VaultReader.createNull(...)`/`TranslationWorker.createNull(...)`/in-memory workspaces, no
filesystem I/O. It references `NotePublicationKind`, which does not exist yet after task 2, so it fails to compile
against a `PublicationKinds` that only knows essay — that is the expected RED state (or, if `PublicationKinds`
already silently blocks unknown kinds gracefully, the test fails at the assertion, not at compile time; either is
an acceptable RED).

- [x] 3.1 Write the failing test:

```java
    private static final String VALID_NOTE = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: note
            publicId: my-note
            id: 91aa-my-note
            title: My Note
            description: A short observation.
            ---
            A short observation body.""";

    @Test
    void blogNoteCompletesPrepareAndApproveThroughTheSamePathAsEssay() {
        VaultRelativePath path = VaultRelativePath.of("blog/my-note.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_NOTE));
        VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        WorkflowStatusEditor workflowStatusEditor = WorkflowStatusEditor.createNull();
        TranslationWorker translationWorker = TranslationWorker.createNull();
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor);

        BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

        assertTrue(prepareResponse.ok());
        PublicationIdentity identity = PublicationIdentity.of("blog", "note", "my-note");
        assertEquals(identity, prepareResponse.identity());

        MarkReviewedHandler markReviewedHandler =
                new MarkReviewedHandler(noteIntake, candidateWorkspace, approvedSnapshotWorkspace, workflowStatusEditor);
        BridgeResponse approveResponse = markReviewedHandler.markReviewed(path, vaultReader);

        assertTrue(approveResponse.ok());
        assertTrue(approvedSnapshotWorkspace.read(identity).isPresent());
    }
```

  Ground the exact fake-constructor names (`VaultAssetReader.createNull()`, `CandidateWorkspace.createNull()`,
  `ApprovedSnapshotWorkspace.createNull()`, `WorkflowStatusEditor.createNull()`, `TranslationWorker.createNull()`)
  against `PrepareHandlerTest`'s existing setup — this project's nullables convention (each production adapter
  ships a `createNull()` fake, per the /nullables skill) means these almost certainly already exist and are
  already used by the essay-path tests in the same file; copy the exact fixture-construction pattern already used
  there rather than guessing signatures. If `TranslationWorker.createNull()` returns a worker whose translated
  title/description/body differ from the Russian source, adjust the assertions above to match its actual
  documented null-translation behaviour (check `TranslationWorker.java`'s `createNull()` factory) rather than
  assuming an identity translation.

- [x] 3.2 Run `mvn -q -o test -Dtest=PrepareHandlerTest` (or the new test class name). Expected: FAIL — either a
      compile error (if `PublicationKinds` doesn't yet expose a way to add `NotePublicationKind`, which it does
      via `installed()`'s hardcoded list) or an assertion failure with a `publicContentType`-field diagnostic,
      since `PublicationKinds.installed()` only knows `blog/essay` until task 4.

## 4. Implement NotePublicationKind (GREEN)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/NotePublicationKind.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java`

- [x] 4.1 Create `NotePublicationKind` — same field contract shape as `EssayPublicationKind` (identity + title +
      description, no required structured body, matching `site/src/content.config.ts`'s `blogNote`'s all-optional
      `observation`/`model`/`boundary`/`experiment` fields — see design.md's Goals):

```java
package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class NotePublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"));

    @Override
    public String collection() {
        return "blog";
    }

    @Override
    public String contentType() {
        return "note";
    }

    @Override
    public String routePrefix() {
        return "notes";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        return AdmittedPublication.accepted(
                this, PublicationIdentity.of(collection(), contentType(), publicId), sourceId, title, description);
    }

    @Override
    public KindContract contract() {
        List<FieldContract> requiredFields = new ArrayList<>();
        requiredFields.add(FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"));
        requiredFields.add(FieldContract.allowedValue("publicCollection", FieldContract.Type.STRING, collection()));
        requiredFields.add(FieldContract.allowedValue("publicContentType", FieldContract.Type.STRING, contentType()));
        for (FieldRule rule : FIELD_RULES) {
            requiredFields.add(toFieldContract(rule));
        }
        return KindContract.of(collection(), contentType(), requiredFields, List.of());
    }

    private String requireValidPublicId(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        String publicId = frontmatter.string("publicId").filter(this::isSlug).orElse(null);
        if (publicId == null) {
            diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
        }
        return publicId;
    }

    private boolean isSlug(String candidate) {
        return PUBLIC_ID_SLUG.matcher(candidate).matches();
    }

    private String requireNonBlank(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        String value = frontmatter.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
        if (value == null) {
            diagnostics.add(Diagnostic.blocking(key, "Note has no " + key + "."));
        }
        return value;
    }

    private static FieldContract toFieldContract(FieldRule rule) {
        return switch (rule.kind()) {
            case MUST_EQUAL ->
                    FieldContract.allowedValue(rule.field(), FieldContract.Type.STRING, rule.literalValue());
            case MUST_MATCH -> FieldContract.matchingPattern(rule.field(), rule.pattern().pattern());
            case NON_BLANK -> FieldContract.nonBlank(rule.field());
        };
    }
}
```

  `EssayPublicationKind` and `NotePublicationKind` are near-identical on purpose (design.md D1's alternative
  considered) — do not extract a shared abstract base or rule-table this slice. `S17b`'s `claim` kind has a
  genuinely different required-field shape (`statement`, `claimKinds`, `supports`/`opposes`/etc.) and will be the
  first real evidence for whether a shared abstraction is warranted.

- [x] 4.2 Register it in `PublicationKinds.installed()`:

```java
    public static PublicationKinds installed() {
        return new PublicationKinds(List.of(new EssayPublicationKind(), new NotePublicationKind()));
    }
```

- [x] 4.3 Run `mvn -q -o test` from `publication-exporter/`. Expected: PASS — task 3's acceptance test goes green,
      and the full essay suite stays green (essay fixtures are untouched; `PublicationKinds.forIdentity("blog",
      "essay")` still resolves to the same `EssayPublicationKind`).

- [x] 4.4 Add the mirror-image kind-level fixture test for `NotePublicationKind`, following the exact structure of
      `EssayPublicationKindFixture`/`EssayPublicationKindFixtures`/`EssayPublicationKindTest` from task 1.8 —
      create `NotePublicationKindFixture.java`, `NotePublicationKindFixtures.java` (fixtures: `validNote`,
      `invalidPublicId`, `missingSourceId`, `missingTitle`, `blankDescription`, `missingPublicIdAndSourceId` — the
      same shape as essay's kind-level fixtures, with `publicContentType: note` and no essay-only body fields),
      and `NotePublicationKindTest.java`.

- [x] 4.5 Run `mvn -q -o test` from `publication-exporter/`. Expected: PASS.

- [x] 4.6 Commit:

```bash
git add publication-exporter/src
git commit -m "feat(exporter): add NotePublicationKind for blog/note admission (ADM-03, ADM-04)"
```

## 5. Route policy for blog/note links + release frontmatter projection + contract output

**Files:**
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java` (or
  wherever a `PrepareHandler`-level acceptance test exercises linking between two notes)
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java`
- Modify: `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java`

**Design context (design.md D6):** `site/src/pages/{ru,en}/notes/[id].astro` already exists — `blog/note`'s real
route is `/notes/{publicId}/`, not `/essays/{publicId}/`. Task 2.2 already made `PublicNoteIndex` compute the route
from `intake.kind().routePrefix()`, and task 4.1 gave `NotePublicationKind.routePrefix()` = `"notes"`. This task
adds the acceptance evidence proving it, per specs/public-content-model/spec.md's new PCM-03 scenario.

**PCM-02 grounding correction:** the release-time frontmatter projection this requirement actually governs is
`FilesystemManagedSiteInstaller.frontmatter(PublicationIdentity, CandidateSnapshot, String locale)`
(`site/FilesystemManagedSiteInstaller.java:437-452`) — not the discovery-only `ManifestEntry` used by
`write-publication-manifest` (ADM-05), which carries no content fields at all. This method is **already
kind-neutral**: it writes `contentType: identity.publicContentType()` generically and only ever emits
`id`/`title`/`description`/`publish`/`contentType`/`language`/`sourceLanguage`/`sourceHash`/`translationStatus`/
`translationOf` — no essay-only fields (`sections`, `abstract`, `closing`, etc.) are written by the exporter at
all today, for any kind. **No production change is needed in this file for PCM-02** — only a test proving a
`blog/note` identity produces `contentType: "note"` and the same shared-field set as essay, closing the coverage
gap rather than fixing a bug.

- [x] 5.1 Add a failing-then-passing test proving a link to a `blog/note` target resolves to `/notes/{id}/`. If
      `LinkResolverTest` builds `PublicNoteIndex` directly via its `Map`-based constructor (unaffected by task 2's
      changes), add a case there:

```java
    @Test
    void linkToBlogNoteTargetResolvesToNotesRoute() {
        PublicNoteIndex index = new PublicNoteIndex(Map.of("My Note", "/notes/my-note/"));

        LinkResolutionOutcome outcome = LinkResolver.resolve("See [[My Note]].", index);

        assertEquals("See [My Note](/notes/my-note/).", outcome.resolvedBody());
    }
```

  This alone only proves `LinkResolver` renders whatever route `PublicNoteIndex` hands it — it does not exercise
  `PublicNoteIndex.from(...)`'s kind-aware route computation from task 2.2. Also add (in `PrepareHandlerTest` or
  a new integration-style test) an end-to-end case: an essay whose body links (`[[my-note]]`) to an admitted
  `blog/note` fixture in the same `VaultReader.createNull(...)` vault, asserting the prepared candidate's body
  contains `/notes/my-note/`, not `/essays/my-note/`. Ground the exact assertion against `PrepareHandlerTest`'s
  existing link-resolution test (it already has one for essay-to-essay linking — mirror its structure with a
  `blog/note` target instead).

- [x] 5.2 Run the new test(s) first to confirm they pass (task 2.2's change already made this correct — this task
      is closing the coverage gap, not fixing a still-open bug). If any assertion fails, the gap is in task 2.2 or
      4.1 — fix there, not by special-casing here.

- [x] 5.3 Add a PCM-02 test to `FilesystemManagedSiteInstallerTest` proving `contentType: "note"` is projected for
      a `blog/note` identity with the same shared-field set as essay, no essay-only fields. Follow the exact
      existing pattern (`IDENTITY`/`SNAPSHOT` constants and `installWritesBothLocaleFilesAndTheManifestIntoAbsentManagedRoots`,
      lines 33-99 of that file):

```java
    private static final PublicationIdentity NOTE_IDENTITY = PublicationIdentity.of("blog", "note", "my-note");
    private static final CandidateSnapshot NOTE_SNAPSHOT = CandidateSnapshot.of(
            "# RU note body", "# EN note body", "RU note title", "EN note title",
            "RU note description.", "EN note description.",
            ReferenceMap.empty(NOTE_IDENTITY, "note-ru-hash", "note-en-hash",
                    "note-ru-title-hash", "note-en-title-hash",
                    "note-ru-description-hash", "note-en-description-hash"));

    @Test
    void installProjectsBlogNoteContentTypeWithTheSameSharedFieldSetAsEssay() throws Exception {
        FilesystemManagedSiteInstaller installer = new FilesystemManagedSiteInstaller(siteRoot);

        installer.install(NOTE_IDENTITY, NOTE_SNAPSHOT);

        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-note.md");
        assertEquals("---\n"
                + "id: \"my-note\"\n"
                + "title: \"RU note title\"\n"
                + "description: \"RU note description.\"\n"
                + "publish: true\n"
                + "contentType: \"note\"\n"
                + "language: \"ru\"\n"
                + "sourceLanguage: \"ru\"\n"
                + "sourceHash: \"note-ru-hash\"\n"
                + "translationStatus: \"source\"\n"
                + "---\n"
                + "# RU note body", Files.readString(ruFile, StandardCharsets.UTF_8));
    }
```

  This is expected to pass without any production change — `FilesystemManagedSiteInstaller.frontmatter(...)`
  already writes `contentType: identity.publicContentType()` generically (see this section's grounding note
  above). If it fails, that means the method is not as kind-neutral as design.md assumed; fix
  `FilesystemManagedSiteInstaller` to derive `contentType` from the identity rather than any hardcoded literal,
  and re-run.

- [x] 5.4 Add a `blog/note` row to `PublicationContractConformanceTest`'s shared fixture table (mirroring however
      it currently enumerates the essay row) so contract/runtime agreement is proven for both kinds — read the
      test's current structure first (it was written in S15, per `dec-20260811-ad8fc743`) and follow its existing
      pattern exactly rather than introducing a new fixture format.

- [x] 5.5 Run `mvn -q -o test` from `publication-exporter/`. Expected: PASS.

- [x] 5.6 Commit:

```bash
git add publication-exporter/src
git commit -m "test(exporter): prove blog/note link routing, frontmatter projection, and contract conformance (PCM-02, PCM-03, ADM-06)"
```

## 6. Full-suite verification and reflect-config check

- [x] 6.1 Run the complete suite once more from a clean state: `cd publication-exporter && mvn -q -o clean test`.
      Expected: PASS, zero failures, zero errors.

- [x] 6.2 Confirm no new `@JsonProperty`-annotated class was introduced without a corresponding
      `publication-exporter/src/main/resources/META-INF/native-image/reflect-config.json` entry (see this file's
      global constraints — expected: none needed, `AdmittedPublication`/`PublicationKind`/`PublicationKinds` are
      all internal and never serialized).

- [x] 6.3 Confirm `git status` shows a clean, fully-committed working tree with `EssayAdmission.java` and
      `EssayPublicationContract.java` deleted (not just unreferenced) and no stray files.

- [x] 6.4 Re-read specs/publication-admission/spec.md and specs/public-content-model/spec.md in this change folder
      and confirm every scenario in both delta files is covered by a passing test written in this plan (ADM-03's
      two new/modified scenarios, ADM-04's two, PCM-02's one, PCM-03's one, PCM-06's one). List any gap found and
      close it before treating this slice as done.
