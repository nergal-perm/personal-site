<!--
Global constraints for every task:
- Module: publication-exporter (Maven, Java 17). Fresh verification command for completion: `mvn -f publication-exporter/pom.xml test`.
- Outside-in TDD: every production change starts from a failing test or acceptance assertion that proves the new curated-page behavior.
- No generic schema/reflection framework, no new collection/content-type switches in generic orchestration, and no changes to exporter-java/ or site/.
- No support for any editorialPage value other than "about" this slice (G7, dec-20260813-88dd478e). The other eight legacy page keys are named in the contract and admission's known-key set, but rejected with a distinct "not yet supported" diagnostic.
- `editorial/curated_page` has no `description` requirement and does not populate `topics`/`links` — matching every other implemented kind, none of which populate those two fields yet either (do not add them "for parity" with the legacy exporter; there is no such parity requirement from any currently-implemented kind).
- Follow the current Null*/createNull() testing style. If an interface or constructor used by a fake changes, update the fake in the same task.
- Keep every new class one focused abstraction: final class, composition over inheritance, no type introspection (`instanceof`/casts), and intention-revealing method names (SBPP-BEH-18, Elegant Objects 3.7).
- Every method stays at one level of abstraction (SBPP-BEH-01, Composed Method): a method body reads as a table of contents, not an implementation. Extract a private method for any sub-step that can be named.
- Constructors stay code-free (Elegant Objects 1.3); validation and derivation live in named methods called from `admit()`, not inline in field assignment.
- Accepted design trade-off (Riel 4.1 departure, noted not silently absorbed): Task 1 makes `site` depend on `admission` (`FilesystemManagedSiteInstaller` looks up a `PublicationKind` via `PublicationKinds`) while `admission` already depends on `site` for `YamlScalar`/`BracketIndexedFields` (established since S17c). This two-package coupling is the direct, minimal consequence of design.md D4's "kinds own their projection to managed content" rule — the installer must ask the kind, and the kind's default rendering must reuse the existing YAML formatting utilities. No third package or interface-inversion layer is introduced to avoid it; that would be exactly the premature abstraction the plan's minimal-slice discipline forbids for a two-package, one-call-site coupling.
-->

## Task 1 — Artifact-projection seam: `ManagedArtifact`, `PublicationKind.projectManagedArtifact`, kind-aware `FilesystemManagedSiteInstaller`

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ManagedArtifact.java` (create), `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKind.java` (modify — add default method), `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/BracketIndexedFields.java` (modify — widen to `public`), `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java` (modify), `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java` (no change expected — this task's own regression gate).

This task is a **behavior-preserving extraction** for all six existing kinds. No existing acceptance fixture's expected output changes. Do this task first and run the full suite at the end before touching anything else — it is the foundation every later task's `FilesystemManagedSiteInstaller` interaction depends on.

- [x] 1.1 Create `ManagedArtifact`, an immutable value type describing one locale's rendered site payload:

  ```java
  package dev.eugene.publicationexporter.admission;

  import java.util.Objects;

  public final class ManagedArtifact {

      private final String relativePath;
      private final String content;
      private final String collisionMarkerLine;

      private ManagedArtifact(String relativePath, String content, String collisionMarkerLine) {
          this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
          this.content = Objects.requireNonNull(content, "content");
          this.collisionMarkerLine = Objects.requireNonNull(collisionMarkerLine, "collisionMarkerLine");
      }

      public static ManagedArtifact of(String relativePath, String content, String collisionMarkerLine) {
          return new ManagedArtifact(relativePath, content, collisionMarkerLine);
      }

      public String relativePath() {
          return relativePath;
      }

      public String content() {
          return content;
      }

      /**
       * The exact line this artifact's own kind marker appears as, scanned for verbatim in an
       * existing file at the same path before replacement, to detect two different kinds
       * colliding on one (collection, publicId) address. See FilesystemManagedSiteInstaller's
       * existing requireNoKindCollision.
       */
      public String collisionMarkerLine() {
          return collisionMarkerLine;
      }

      @Override
      public boolean equals(Object other) {
          if (this == other) {
              return true;
          }
          if (!(other instanceof ManagedArtifact that)) {
              return false;
          }
          return relativePath.equals(that.relativePath) && content.equals(that.content)
                  && collisionMarkerLine.equals(that.collisionMarkerLine);
      }

      @Override
      public int hashCode() {
          return Objects.hash(relativePath, content, collisionMarkerLine);
      }
  }
  ```

- [x] 1.2 Add the default `projectManagedArtifact` method to `PublicationKind`, moving `FilesystemManagedSiteInstaller`'s current `frontmatter(...)`/`markdownFile(...)` logic in verbatim as private static helpers of the interface (Java 17 permits private interface methods). This reproduces today's exact byte-for-byte Markdown+frontmatter output for every kind that does not override it:

  ```java
  package dev.eugene.publicationexporter.admission;

  import dev.eugene.publicationexporter.bridge.PublicationIdentity;
  import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
  import dev.eugene.publicationexporter.contract.KindContract;
  import dev.eugene.publicationexporter.note.MarkdownNote;
  import dev.eugene.publicationexporter.reference.PublicField;
  import dev.eugene.publicationexporter.site.BracketIndexedFields;
  import dev.eugene.publicationexporter.site.YamlScalar;

  import java.util.List;

  public interface PublicationKind {

      String collection();

      String contentType();

      String routePrefix();

      AdmittedPublication admit(MarkdownNote frontmatter);

      KindContract contract();

      default ManagedArtifact projectManagedArtifact(
              PublicationIdentity identity, CandidateSnapshot approved, String locale) {
          return ManagedArtifact.of(
                  markdownRelativePath(identity, locale),
                  markdownContent(identity, approved, locale),
                  markdownCollisionMarkerLine(identity));
      }

      private static String markdownRelativePath(PublicationIdentity identity, String locale) {
          return "src/content/" + identity.publicCollection() + "/" + locale + "/" + identity.publicId() + ".md";
      }

      private static String markdownCollisionMarkerLine(PublicationIdentity identity) {
          return "contentType: " + YamlScalar.doubleQuoted(identity.publicContentType());
      }

      private static String markdownContent(PublicationIdentity identity, CandidateSnapshot approved, String locale) {
          boolean isRu = "ru".equals(locale);
          StringBuilder yaml = new StringBuilder("---\n");
          appendYamlString(yaml, "id", identity.publicId());
          List<PublicField> fields = isRu ? approved.ruFields() : approved.enFields();
          yaml.append(BracketIndexedFields.render(fields, field -> appendYamlString(yaml, field.key(), field.value())));
          yaml.append("publish: true\n");
          appendYamlString(yaml, "contentType", identity.publicContentType());
          appendYamlString(yaml, "language", locale);
          appendYamlString(yaml, "sourceLanguage", "ru");
          appendYamlString(yaml, "sourceHash", approved.referenceMap().ruHash());
          appendYamlString(yaml, "translationStatus", isRu ? "source" : "generated");
          if (!isRu) {
              appendYamlString(yaml, "translationOf", identity.publicId());
          }
          if (!approved.structuredData().isBlank()) {
              yaml.append(approved.structuredData());
          }
          yaml.append("---\n");
          String body = isRu ? approved.ruBody() : approved.enBody();
          return yaml.append(body).toString();
      }

      private static void appendYamlString(StringBuilder yaml, String key, String value) {
          yaml.append(key).append(": ").append(YamlScalar.doubleQuoted(value)).append('\n');
      }
  }
  ```

  Note the exact reassembly: the original `frontmatter(...)` returned only the `---\n...---\n` block and the caller (`writeLocaleFile`) concatenated `frontmatter(...) + body` separately. `markdownContent` above inlines both, producing the identical concatenated string — verify this by keeping every existing `FilesystemManagedSiteInstallerTest` assertion unchanged and green (they assert on the fully installed file's content, so a reassembly mistake fails loudly).

- [x] 1.3 Widen `BracketIndexedFields` from package-private to `public` (one-word change, no other modification — it already only exposes one static method `render(...)`, unaffected by the visibility change since every existing caller stays in-package):

  ```java
  // before:
  final class BracketIndexedFields {
  // after:
  public final class BracketIndexedFields {
  ```

- [x] 1.4 Refactor `FilesystemManagedSiteInstaller`: add a `PublicationKinds` field via a new secondary constructor, keep the existing single-arg constructor as a shortcut delegating to it (SBPP-BEH-04 Shortcut Constructor Method — zero existing call sites anywhere in the codebase need to change), and replace the three call sites that used the removed `markdownFile(...)`/`frontmatter(...)`/hardcoded collision-line logic with kind lookups:

  ```java
  // Add imports:
  import dev.eugene.publicationexporter.admission.ManagedArtifact;
  import dev.eugene.publicationexporter.admission.PublicationKind;
  import dev.eugene.publicationexporter.admission.PublicationKinds;

  // Add field:
  private final PublicationKinds publicationKinds;

  // Replace the existing single constructor with two:
  public FilesystemManagedSiteInstaller(Path siteRoot) {
      this(siteRoot, PublicationKinds.installed());
  }

  public FilesystemManagedSiteInstaller(Path siteRoot, PublicationKinds publicationKinds) {
      this.stagedInstall = StagedDirectoryInstall.rootedAtCanonical(
              canonicalizeThroughNearestExistingAncestor(Objects.requireNonNull(siteRoot, "siteRoot")));
      this.publicationKinds = Objects.requireNonNull(publicationKinds, "publicationKinds");
  }
  ```

  Replace `stageLocaleFiles`/`writeLocaleFile` (which called `frontmatter(...)` + `approvedSnapshot.ruBody()/enBody()` directly) to go through the kind instead:

  ```java
  private void stageLocaleFiles(
          Path staging, PublicationIdentity identity, CandidateSnapshot approvedSnapshot) throws IOException {
      PublicationKind kind = requireKind(identity);
      writeLocaleFile(staging, kind, identity, approvedSnapshot, "ru");
      writeLocaleFile(staging, kind, identity, approvedSnapshot, "en");
  }

  private void writeLocaleFile(Path staging, PublicationKind kind, PublicationIdentity identity,
          CandidateSnapshot approvedSnapshot, String locale) throws IOException {
      ManagedArtifact artifact = kind.projectManagedArtifact(identity, approvedSnapshot, locale);
      writeStagedFile(staging, locale + (isJson(artifact) ? ".json" : ".md"), artifact.content());
  }
  ```

  This introduces a locale-file-extension question the original code never had (it always wrote `ru.md`/`en.md` as staging filenames, independent of the final relative path, then `installManagedGeneration` moved `ru.md`/`en.md` from staging to the real destination computed by `markdownFile(...)`). Simplify by keeping staging filenames fixed (`ru.md`/`en.md` are just temporary names inside the staging directory, never the real destination — the original code already treated them as opaque temp names) and instead deriving the **real destination path** from `ManagedArtifact.relativePath()` directly:

  ```java
  private void installManagedGeneration(Path staging, PublicationIdentity identity, String locale... )
  ```

  Concretely: keep `writeLocaleFile`'s staged filename as the fixed `locale + ".md"` (it is deleted immediately after the move regardless of extension — this is an internal temp name, not observable), and change `installManagedGeneration`'s two destination-path computations from calling `markdownFile(identity, "ru"/"en")` to instead re-deriving each locale's real destination from the same `kind.projectManagedArtifact(identity, approvedSnapshot, locale).relativePath()` call already made in `stageLocaleFiles` — thread the two `ManagedArtifact` values (ru, en) from `stageLocaleFiles` into `installManagedGeneration` as parameters instead of recomputing `markdownFile(...)`:

  ```java
  private void installFromStaging(PublicationIdentity identity, CandidateSnapshot approvedSnapshot,
          Path ruDestination, Path enDestination) {
      // ruDestination/enDestination are now passed in from installWithOutcome, computed once
      // via kind.projectManagedArtifact(...).relativePath() before staging begins — see below.
      ...
  }
  ```

  Restructure `installWithOutcome` to resolve both destinations up front (this mirrors the original code's own shape, which already computed `ruDestination`/`enDestination` before staging):

  ```java
  @Override
  public ManagedSiteInstallOutcome installWithOutcome(
          PublicationIdentity identity, CandidateSnapshot approvedSnapshot) {
      requireInstallationInputs(identity, approvedSnapshot);
      PublicationKind kind = requireKind(identity);
      Path ruDestination = destinationFile(kind.projectManagedArtifact(identity, approvedSnapshot, "ru"));
      Path enDestination = destinationFile(kind.projectManagedArtifact(identity, approvedSnapshot, "en"));
      return withInstallationLock(identity, () -> {
          boolean recovered = recoverIfNeeded(ruDestination, enDestination);
          requireNoKindCollision(identity, kind, ruDestination, "ru", approvedSnapshot);
          requireNoKindCollision(identity, kind, enDestination, "en", approvedSnapshot);
          try {
              installFromStaging(identity, kind, approvedSnapshot, ruDestination, enDestination);
          } catch (RuntimeException failure) {
              if (recovered) {
                  throw ManagedSiteInstallationFailedAfterRecoveryException.afterRecovery(failure);
              }
              throw failure;
          }
          return recovered
                  ? ManagedSiteInstallOutcome.RECOVERED_AND_INSTALLED
                  : ManagedSiteInstallOutcome.INSTALLED;
      });
  }

  private Path destinationFile(ManagedArtifact artifact) {
      return stagedInstall.canonicalRoot().resolve(artifact.relativePath()).normalize();
  }

  private PublicationKind requireKind(PublicationIdentity identity) {
      return publicationKinds.forIdentity(identity.publicCollection(), identity.publicContentType())
              .orElseThrow(() -> new IllegalStateException(
                      "No installed PublicationKind for " + identity.publicCollection()
                              + "/" + identity.publicContentType()));
  }
  ```

  Note `requireKind` throwing `IllegalStateException` for an unknown kind is correct and matches the existing project invariant: by the time an approved snapshot reaches site installation, admission has already validated its kind exists (an unknown kind here would mean approved/candidate data outlived the exporter edition that admitted it — a genuine "should never happen" defensive check, not a new user-facing validation path).

  Update `installFromStaging`/`installManagedGeneration` signatures to accept `PublicationKind kind` and re-derive content via `kind.projectManagedArtifact(...)` instead of the deleted `frontmatter(...)`/direct-body calls (the staging step already writes `artifact.content()` verbatim from `writeLocaleFile`, above — `installManagedGeneration`'s own body does not need `kind` beyond the destination paths already resolved in `installWithOutcome`, so thread `ruDestination`/`enDestination` through as today, no `kind` parameter needed past this point).

  Update `requireNoKindCollision` to take the artifact's `collisionMarkerLine()` instead of building the line inline:

  ```java
  private void requireNoKindCollision(Path destination, String collisionMarkerLine) {
      Path resolved = resolveWithinSiteRoot(destination);
      if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
          return;
      }
      List<String> lines;
      try {
          lines = Files.readAllLines(resolved, StandardCharsets.UTF_8);
      } catch (IOException unreadable) {
          return;
      }
      boolean matchesIncomingKind = lines.stream().anyMatch(collisionMarkerLine::equals);
      boolean hasAnyMarkerLine = lines.stream().anyMatch(line -> line.startsWith("contentType"));
      if (hasAnyMarkerLine && !matchesIncomingKind) {
          throw new ManagedSiteKindCollisionException(identity, resolved);
      }
  }
  ```

  Keep every other method (`recoverIfNeeded`, `ensurePayloadRoots`, locking, backup/rollback, `resolveWithinSiteRoot`, etc.) exactly as-is — none of them depend on the artifact shape, only on `Path` destinations, which remain `Path` throughout.

- [x] 1.5 Run `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemManagedSiteInstallerTest` and confirm every existing test (essay, note, claim, book, concept, album) still passes unchanged. Then run the complete `mvn -f publication-exporter/pom.xml test` and confirm no regression anywhere. If any existing assertion fails, the extraction introduced a byte-level difference — fix the extraction, do not adjust the test.

## Task 2 — `EnglishCandidateValidator`: blank body is only a worker failure when the source had one

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidator.java` (modify), `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/EnglishCandidateValidatorTest.java` (extend — create if it does not already exist as a dedicated file; if validator coverage currently lives inside `PrepareHandlerTest`, add the new case there instead, matching existing convention).

- [x] 2.1 Write the failing test first: `validate("", "", List.of(PublicField.of("title", "")))` — wait, a blank *field* is still always a failure; the new behavior only concerns `enBody`. Write: given `ruBody = ""` and `enBody = ""` (both blank) and non-blank fields, `validate` returns `Result.ok()` with no body diagnostic (previously this would have failed with `"Translation worker produced a blank body."` even though there was never anything to translate). Also keep/add a case proving the check still fires: given `ruBody` non-blank and `enBody = ""`, `validate` still returns an invalid `Result` containing `"Translation worker produced a blank body."`.

  ```java
  @Test
  void blankSourceAndTranslatedBodyIsNotAWorkerFailure() {
      EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
              "", List.of(PublicField.of("title", "Заголовок")),
              "", List.of(PublicField.of("title", "Title")));
      assertTrue(result.valid(), result.diagnostics().toString());
  }

  @Test
  void blankTranslatedBodyIsStillAWorkerFailureWhenSourceHadContent() {
      EnglishCandidateValidator.Result result = EnglishCandidateValidator.validate(
              "Русский текст.", List.of(PublicField.of("title", "Заголовок")),
              "", List.of(PublicField.of("title", "Title")));
      assertTrue(result.diagnostics().contains("Translation worker produced a blank body."));
  }
  ```

- [x] 2.2 Run the new test file/method and confirm the first case currently FAILS (with the pre-fix code) and the second currently PASSES — this proves the test actually exercises the gap being fixed.

- [x] 2.3 Fix `blankFieldDiagnostics` to accept `ruBody` and gate the body check on it being non-blank; update its one call site inside `validate(String, List, String, List)`:

  ```java
  // in validate(...):
  diagnostics.addAll(blankFieldDiagnostics(ruBody, enBody, enFields));

  // method signature and body:
  private static List<String> blankFieldDiagnostics(String ruBody, String enBody, List<PublicField> enFields) {
      List<String> diagnostics = new ArrayList<>();
      if (!ruBody.isBlank() && enBody.isBlank()) {
          diagnostics.add("Translation worker produced a blank body.");
      }
      for (PublicField field : enFields) {
          if (field.value().isBlank()) {
              diagnostics.add("Translation worker produced a blank " + field.key() + ".");
          }
      }
      return diagnostics;
  }
  ```

- [x] 2.4 Run `mvn -f publication-exporter/pom.xml test -Dtest=EnglishCandidateValidatorTest,PrepareHandlerTest` (adjust the test class name to wherever step 2.1's test actually landed) and confirm both new cases pass, then run the complete `mvn -f publication-exporter/pom.xml test` and confirm zero regressions — every existing kind's `ruBody` is non-blank in its fixtures, so this change cannot alter any existing test's outcome.

## Task 3 — `AboutPageBody` parser and `CuratedPagePublicationKind`

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/AboutPageBody.java` (create), `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/CuratedPagePublicationKind.java` (create), `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java` (modify — register), `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/AboutPageBodyTest.java` (create), `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/CuratedPagePublicationKindTest.java` (create), `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/CuratedPagePublicationKindFixture.java` and `CuratedPagePublicationKindFixtures.java` (create, matching `ConceptPublicationKindFixture(s)`'s exact shape).

- [x] 3.1 Write `AboutPageBodyTest` first, using the exact grammar fixture ported from `exporter-java`'s `EditorialParserTest` (already read during design; reproduced here verbatim as the compatibility-oracle fixture per G7):

  ```java
  package dev.eugene.publicationexporter.admission;

  import org.junit.jupiter.api.Test;

  import java.util.List;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertThrows;

  class AboutPageBodyTest {

      private static final String VALID_BODY = """
              ## Кратко

              Кратко.

              ## Eyebrow

              Бровь.

              ## Лид

              Лид.

              ## Принципы

              ### Первый

              Принцип.

              ## Колофон

              Колофон.
              """;

      @Test
      void parsesEveryRequiredSection() {
          AboutPageBody parsed = AboutPageBody.parse(VALID_BODY);
          assertEquals("Кратко.", parsed.summary());
          assertEquals("Бровь.", parsed.eyebrow());
          assertEquals("Лид.", parsed.lead());
          assertEquals("Колофон.", parsed.colophon());
          assertEquals(List.of(new AboutPageBody.Principle("Первый", "Принцип.")), parsed.principles());
      }

      @Test
      void missingSectionIsRejected() {
          String withoutColophon = """
                  ## Кратко

                  Кратко.

                  ## Eyebrow

                  Бровь.

                  ## Лид

                  Лид.

                  ## Принципы

                  ### Первый

                  Принцип.
                  """;
          assertThrows(AboutPageBody.MalformedBodyException.class, () -> AboutPageBody.parse(withoutColophon));
      }

      @Test
      void principlesSectionWithNoSubsectionsIsRejected() {
          String withoutPrinciples = """
                  ## Кратко

                  Кратко.

                  ## Eyebrow

                  Бровь.

                  ## Лид

                  Лид.

                  ## Принципы

                  ## Колофон

                  Колофон.
                  """;
          assertThrows(AboutPageBody.MalformedBodyException.class, () -> AboutPageBody.parse(withoutPrinciples));
      }

      @Test
      void multiplePrinciplesParseInOrder() {
          String twoPrinciples = """
                  ## Кратко

                  Кратко.

                  ## Eyebrow

                  Бровь.

                  ## Лид

                  Лид.

                  ## Принципы

                  ### Первый

                  Один.

                  ### Второй

                  Два.

                  ## Колофон

                  Колофон.
                  """;
          assertEquals(
                  List.of(new AboutPageBody.Principle("Первый", "Один."), new AboutPageBody.Principle("Второй", "Два.")),
                  AboutPageBody.parse(twoPrinciples).principles());
      }
  }
  ```

- [x] 3.2 Run the test, confirm it fails with "class AboutPageBody not found". Implement `AboutPageBody` as a small, purpose-built parser for exactly the `about` grammar (not a generalized nine-page-grammar engine — see design.md Non-Goals). Use a minimal line-scanning approach (Composed Method — one named private method per section):

  ```java
  package dev.eugene.publicationexporter.admission;

  import java.util.ArrayList;
  import java.util.List;
  import java.util.Objects;

  public final class AboutPageBody {

      private final String summary;
      private final String eyebrow;
      private final String lead;
      private final List<Principle> principles;
      private final String colophon;

      private AboutPageBody(String summary, String eyebrow, String lead, List<Principle> principles, String colophon) {
          this.summary = summary;
          this.eyebrow = eyebrow;
          this.lead = lead;
          this.principles = List.copyOf(principles);
          this.colophon = colophon;
      }

      public static AboutPageBody parse(String body) {
          List<String> lines = List.of(body.split("\\R", -1));
          String summary = requiredSection(lines, "Кратко");
          String eyebrow = requiredSection(lines, "Eyebrow");
          String lead = requiredSection(lines, "Лид");
          List<Principle> principles = requiredPrinciples(lines);
          String colophon = requiredSection(lines, "Колофон");
          return new AboutPageBody(summary, eyebrow, lead, principles, colophon);
      }

      public String summary() {
          return summary;
      }

      public String eyebrow() {
          return eyebrow;
      }

      public String lead() {
          return lead;
      }

      public List<Principle> principles() {
          return principles;
      }

      public String colophon() {
          return colophon;
      }

      private static String requiredSection(List<String> lines, String heading) {
          int start = sectionStart(lines, heading);
          int end = nextH2Or(lines, start + 1, lines.size());
          String text = joinNonBlank(lines.subList(start + 1, end));
          if (text.isBlank()) {
              throw new MalformedBodyException("## " + heading + " must contain non-empty prose");
          }
          return text;
      }

      private static List<Principle> requiredPrinciples(List<String> lines) {
          int start = sectionStart(lines, "Принципы");
          int end = nextH2Or(lines, start + 1, lines.size());
          List<Principle> principles = new ArrayList<>();
          List<String> section = lines.subList(start + 1, end);
          int index = 0;
          while (index < section.size()) {
              String line = section.get(index);
              if (line.startsWith("### ")) {
                  String title = line.substring(4).strip();
                  int principleEnd = nextH3Or(section, index + 1, section.size());
                  String text = joinNonBlank(section.subList(index + 1, principleEnd));
                  if (title.isBlank() || text.isBlank()) {
                      throw new MalformedBodyException("## Принципы subsection must have a non-blank heading and prose");
                  }
                  principles.add(new Principle(title, text));
                  index = principleEnd;
              } else {
                  index++;
              }
          }
          if (principles.isEmpty()) {
              throw new MalformedBodyException("## Принципы must contain at least one ### subsection");
          }
          return principles;
      }

      private static int sectionStart(List<String> lines, String heading) {
          String marker = "## " + heading;
          for (int i = 0; i < lines.size(); i++) {
              if (lines.get(i).strip().equals(marker)) {
                  return i;
              }
          }
          throw new MalformedBodyException("Missing required heading `" + marker + "`");
      }

      private static int nextH2Or(List<String> lines, int from, int fallback) {
          for (int i = from; i < lines.size(); i++) {
              if (lines.get(i).startsWith("## ")) {
                  return i;
              }
          }
          return fallback;
      }

      private static int nextH3Or(List<String> lines, int from, int fallback) {
          for (int i = from; i < lines.size(); i++) {
              if (lines.get(i).startsWith("### ") || lines.get(i).startsWith("## ")) {
                  return i;
              }
          }
          return fallback;
      }

      private static String joinNonBlank(List<String> lines) {
          return lines.stream()
                  .filter(line -> !line.isBlank())
                  .map(String::strip)
                  .reduce((a, b) -> a + " " + b)
                  .orElse("");
      }

      public record Principle(String title, String text) {
          public Principle {
              Objects.requireNonNull(title, "title");
              Objects.requireNonNull(text, "text");
          }
      }

      public static final class MalformedBodyException extends IllegalArgumentException {
          public MalformedBodyException(String message) {
              super(message);
          }
      }
  }
  ```

  Run the test again and confirm all four cases pass.

- [x] 3.3 Write `CuratedPagePublicationKindTest` (outside-in, no mocking, matching `ConceptPublicationKindTest`'s established style): a fixture with valid identity, `editorialPage: about`, `publicId: about`, `id`, `title`, and a valid `AboutPageBody`-shaped body admits successfully with the expected translated `PublicField` list `[title, summary, eyebrow, lead, principles[0].title, principles[0].text, colophon]` in that exact order and `structuredData` equal to `{"searchable":false,"type":"about"}` (default `publicSearchable` absent → false); a fixture with `publicSearchable: true` admits with `structuredData` equal to `{"searchable":true,"type":"about"}`; a fixture whose `publicId` does not equal `editorialPage` is blocked naming `publicId`; a fixture with `editorialPage: home` (a known-but-unsupported key) is blocked with a diagnostic naming `editorialPage` and stating only `about` is supported; a fixture with `editorialPage: nonsense` (an unknown key) is blocked as an ordinary unsupported identity, same shape as any other malformed `publicContentType`; a fixture missing the body's `## Колофон` section is blocked (delegating to `AboutPageBody.parse`'s own exception, translated into a `Diagnostic`).

- [x] 3.4 Implement `CuratedPagePublicationKind`:

  ```java
  package dev.eugene.publicationexporter.admission;

  import dev.eugene.publicationexporter.bridge.Diagnostic;
  import dev.eugene.publicationexporter.bridge.PublicationIdentity;
  import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
  import dev.eugene.publicationexporter.contract.FieldContract;
  import dev.eugene.publicationexporter.contract.KindContract;
  import dev.eugene.publicationexporter.note.MarkdownNote;
  import dev.eugene.publicationexporter.reference.PublicField;

  import java.util.ArrayList;
  import java.util.List;
  import java.util.Set;
  import java.util.regex.Pattern;

  public final class CuratedPagePublicationKind implements PublicationKind {

      private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
      private static final Set<String> KNOWN_PAGE_KEYS = Set.of(
              "about", "home", "essays", "claims", "notes", "music", "library", "concepts", "now");
      private static final String SUPPORTED_PAGE_KEY = "about";

      @Override
      public String collection() {
          return "editorial";
      }

      @Override
      public String contentType() {
          return "curated_page";
      }

      @Override
      public String routePrefix() {
          return null;
      }

      @Override
      public AdmittedPublication admit(MarkdownNote note) {
          List<Diagnostic> diagnostics = new ArrayList<>();
          String publicId = requireValidPublicId(note, diagnostics);
          String editorialPage = requireSupportedPageKey(note, diagnostics);
          if (publicId != null && editorialPage != null && !publicId.equals(editorialPage)) {
              diagnostics.add(Diagnostic.blocking("publicId", "editorial/curated_page publicId must equal editorialPage."));
          }
          String sourceId = requireNonBlank(note, "id", diagnostics);
          String title = requireNonBlank(note, "title", diagnostics);
          boolean searchable = note.flag("publicSearchable");
          AboutPageBody body = parseBodyOrRecordDiagnostic(note, diagnostics);

          if (!diagnostics.isEmpty()) {
              return AdmittedPublication.blocked(diagnostics);
          }
          return AdmittedPublication.accepted(
                  this,
                  PublicationIdentity.of(collection(), contentType(), publicId),
                  sourceId,
                  translatedFields(title, body),
                  structuredDataFrom(searchable));
      }

      @Override
      public KindContract contract() {
          return KindContract.of(
                  collection(),
                  contentType(),
                  List.of(
                          FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"),
                          FieldContract.allowedValue("publicCollection", FieldContract.Type.STRING, collection()),
                          FieldContract.allowedValue("publicContentType", FieldContract.Type.STRING, contentType()),
                          FieldContract.matchingPattern("publicId", PUBLIC_ID_SLUG.pattern()),
                          FieldContract.allowedValue("editorialPage", FieldContract.Type.STRING, SUPPORTED_PAGE_KEY),
                          FieldContract.nonBlank("id"),
                          FieldContract.nonBlank("title")),
                  List.of(FieldContract.nonBlank("publicSearchable")),
                  List.of("description"),
                  List.of(
                          "## Кратко (summary)",
                          "## Eyebrow (eyebrow)",
                          "## Лид (lead)",
                          "## Принципы with at least one ### subsection (principles)",
                          "## Колофон (colophon)"));
      }

      private List<PublicField> translatedFields(String title, AboutPageBody body) {
          List<PublicField> fields = new ArrayList<>();
          fields.add(PublicField.of("title", title));
          fields.add(PublicField.of("summary", body.summary()));
          fields.add(PublicField.of("eyebrow", body.eyebrow()));
          fields.add(PublicField.of("lead", body.lead()));
          for (int index = 0; index < body.principles().size(); index++) {
              AboutPageBody.Principle principle = body.principles().get(index);
              fields.add(PublicField.of("principles[" + index + "].title", principle.title()));
              fields.add(PublicField.of("principles[" + index + "].text", principle.text()));
          }
          fields.add(PublicField.of("colophon", body.colophon()));
          return List.copyOf(fields);
      }

      private String structuredDataFrom(boolean searchable) {
          return "{\"searchable\":" + searchable + ",\"type\":\"" + SUPPORTED_PAGE_KEY + "\"}";
      }

      private AboutPageBody parseBodyOrRecordDiagnostic(MarkdownNote note, List<Diagnostic> diagnostics) {
          try {
              return AboutPageBody.parse(note.body());
          } catch (AboutPageBody.MalformedBodyException malformed) {
              diagnostics.add(Diagnostic.blocking("body", malformed.getMessage()));
              return null;
          }
      }

      private String requireSupportedPageKey(MarkdownNote note, List<Diagnostic> diagnostics) {
          String editorialPage = note.string("editorialPage").orElse(null);
          if (editorialPage == null || !KNOWN_PAGE_KEYS.contains(editorialPage)) {
              diagnostics.add(Diagnostic.blocking("editorialPage",
                      "must be one of: " + String.join(", ", KNOWN_PAGE_KEYS.stream().sorted().toList())));
              return null;
          }
          if (!editorialPage.equals(SUPPORTED_PAGE_KEY)) {
              diagnostics.add(Diagnostic.blocking("editorialPage",
                      "'" + editorialPage + "' is a known page type but only '" + SUPPORTED_PAGE_KEY
                              + "' is supported in this exporter edition."));
              return null;
          }
          return editorialPage;
      }

      private String requireValidPublicId(MarkdownNote note, List<Diagnostic> diagnostics) {
          String publicId = note.string("publicId").filter(this::isSlug).orElse(null);
          if (publicId == null) {
              diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
          }
          return publicId;
      }

      private boolean isSlug(String candidate) {
          return PUBLIC_ID_SLUG.matcher(candidate).matches();
      }

      private String requireNonBlank(MarkdownNote note, String key, List<Diagnostic> diagnostics) {
          String value = note.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
          if (value == null) {
              diagnostics.add(Diagnostic.blocking(key, "editorial/curated_page has no " + key + "."));
          }
          return value;
      }
  }
  ```

  Check `KindContract.of(...)`'s exact overload signature against `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/KindContract.java` before compiling this — it already supports a 5-arg overload `(collection, contentType, requiredFields, optionalFields, blockedFields, structuredBody)`; confirm parameter order matches (the snippet above assumes `(collection, contentType, requiredFields, optionalFields, blockedFields, structuredBody)` — six positional arguments including the two String identity args).

  `structuredDataFrom` deliberately hand-writes the two-key JSON object rather than pulling in `ObjectMapper` for a fixed two-field literal (SBPP-BEH-17 Intention Revealing Method already makes the shape obvious; a full Jackson round trip for two known keys would be over-engineering for this exact fixed shape — revisit only if a second kind needs `structuredData` in JSON and the duplication becomes real).

- [x] 3.5 Create `CuratedPagePublicationKindFixture`/`CuratedPagePublicationKindFixtures` (matching `ConceptPublicationKindFixture(s)`'s exact shape: `accepted(name, noteSource)` / `blocked(name, noteSource)` static factories, `all()` static list). Include: an accepted fixture with the minimal valid `about` body and no `publicSearchable`; an accepted fixture with `publicSearchable: true` and two `### ` principles; a blocked fixture with `editorialPage: now` (known, unsupported); a blocked fixture with `publicId` not matching `editorialPage`; a blocked fixture with a missing `## Колофон` section. Register `new CuratedPagePublicationKind()` in `PublicationKinds.installed()`.

- [x] 3.6 Run `mvn -f publication-exporter/pom.xml test -Dtest=AboutPageBodyTest,CuratedPagePublicationKindTest` and confirm all pass. Run the full `mvn -f publication-exporter/pom.xml test` once to confirm no existing kind's suite regressed.

## Task 4 — JSON artifact projection, contract conformance, and site-projection coverage

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/CuratedPagePublicationKind.java` (modify — add `projectManagedArtifact` override), `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/CuratedPageJson.java` (create), `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java` (extend), `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java` (extend), `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java` (extend).

**Sequencing note:** Task 3 deliberately left `CuratedPagePublicationKind` using the inherited (Markdown) `projectManagedArtifact` default from Task 1, since Task 3 was scoped to admission only. This task adds the real JSON override first (4.1), before any test in this task or Task 5 can exercise JSON installation — Task 4.2's own test requires the override to exist.

- [x] 4.1 Add the `projectManagedArtifact` override to `CuratedPagePublicationKind`:

  ```java
  @Override
  public ManagedArtifact projectManagedArtifact(
          PublicationIdentity identity, CandidateSnapshot approved, String locale) {
      boolean isRu = "ru".equals(locale);
      List<PublicField> fields = isRu ? approved.ruFields() : approved.enFields();
      String json = CuratedPageJson.render(identity, fields, approved.structuredData(), locale);
      return ManagedArtifact.of(
              "src/data/pages/" + locale + "/" + identity.publicId() + ".json",
              json,
              "\"contentType\":\"" + contentType() + "\"");
  }
  ```

  Implement `CuratedPageJson` (new, `admission` package, package-visible is enough — only `CuratedPagePublicationKind` calls it) using Jackson's `ObjectMapper`/`LinkedHashMap` to guarantee stable key order, matching this project's established `write-publication-contract` convention of serializing a typed value rather than hand-building JSON strings for anything beyond a two-key literal:

  ```java
  package dev.eugene.publicationexporter.admission;

  import com.fasterxml.jackson.databind.ObjectMapper;
  import dev.eugene.publicationexporter.bridge.PublicationIdentity;
  import dev.eugene.publicationexporter.reference.PublicField;

  import java.util.ArrayList;
  import java.util.LinkedHashMap;
  import java.util.List;
  import java.util.Map;

  final class CuratedPageJson {

      private static final ObjectMapper MAPPER = new ObjectMapper();

      private CuratedPageJson() {
      }

      static String render(PublicationIdentity identity, List<PublicField> fields, String structuredData,
              String locale) {
          try {
              Map<String, Object> document = new LinkedHashMap<>();
              document.put("id", identity.publicId());
              document.put("type", "about");
              document.put("language", locale);
              document.put("title", fieldValue(fields, "title"));
              document.put("summary", fieldValue(fields, "summary"));
              document.put("eyebrow", fieldValue(fields, "eyebrow"));
              document.put("lead", fieldValue(fields, "lead"));
              document.put("principles", principlesFrom(fields));
              document.put("colophon", fieldValue(fields, "colophon"));
              document.put("searchable", structuredData.contains("\"searchable\":true"));
              return MAPPER.writeValueAsString(document);
          } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
              throw new IllegalStateException("Curated page JSON serialization failed", impossible);
          }
      }

      private static String fieldValue(List<PublicField> fields, String key) {
          return PublicField.value(fields, key).orElse("");
      }

      private static List<List<String>> principlesFrom(List<PublicField> fields) {
          List<List<String>> principles = new ArrayList<>();
          int index = 0;
          while (true) {
              String title = PublicField.value(fields, "principles[" + index + "].title").orElse(null);
              if (title == null) {
                  break;
              }
              String text = PublicField.value(fields, "principles[" + index + "].text").orElse("");
              principles.add(List.of(title, text));
              index++;
          }
          return principles;
      }
  }
  ```

  Note `structuredData.contains("\"searchable\":true")` is a pragmatic read of the fixed two-key literal `structuredDataFrom` writes in Task 3 — acceptable because both producer and consumer are the same class family and the shape is deliberately fixed and tested end-to-end here; do not generalize this into a JSON-parsing round trip unless a second field is ever added to `structuredData` for this kind.

- [x] 4.2 Extend `PublicationContractConformanceTest`'s shared fixture table with `editorial/curated_page` cases, reusing `CuratedPagePublicationKindFixtures.all()` exactly as every sibling kind already does (copy the `conceptContractVerdictAgreesWithFixtureAndRuntimeValidator`-shaped method, renamed for curated pages). This kind's contract has a non-empty `structuredBody` list for the first time — if the conformance harness has never exercised that field before, verify it actually compares `contract().structuredBody()` against something (if it currently ignores `structuredBody` entirely, that is fine and expected: `structuredBody` is documentation for authoring tools, not an input the runtime validator re-derives, since `AboutPageBody.parse` already independently enforces the same sections — do not invent a redundant cross-check between the two representations unless a real drift is found).

- [x] 4.3 Add a `FilesystemManagedSiteInstallerTest` case proving `editorial/curated_page` installs as JSON, not Markdown, using the 4.1 override: build an approved `CandidateSnapshot` with `ruBody`/`enBody` both `""`, `ruFields`/`enFields` matching `CuratedPagePublicationKind`'s translated-field shape (title, summary, eyebrow, lead, one principle's title/text, colophon) in RU and EN, and `structuredData` equal to `{"searchable":true,"type":"about"}`. Call `new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved)` with `identity = PublicationIdentity.of("editorial", "curated_page", "about")`. Assert: `siteRoot.resolve("src/data/pages/ru/about.json")` and `.../en/about.json` both exist (not `src/content/editorial/...`); each parses as valid JSON (use the project's existing JSON test-parsing convention — check how `CheckContentGateContractTest` or `SiteReleaseManifest` tests parse JSON for the established pattern) containing `"id":"about"`, `"type":"about"`, `"language":"ru"`/`"en"` respectively, the RU/EN translated field values, and `"searchable":true`; assert the file contains no `topics`/`links` key at all (proving the earlier topics/links scope-narrowing decision is enforced, not just documented).

- [x] 4.4 Add a case proving the collision guard works for the new JSON path shape too: install `editorial/curated_page` `about` at a site root, then attempt to install a different identity that would resolve to the same relative path with a different `collisionMarkerLine` (construct this directly against `FilesystemManagedSiteInstaller`, not through a second real kind — no second curated-page-shaped kind exists yet to collide with in this exporter edition, so this test exercises the guard mechanically: pre-seed `src/data/pages/ru/about.json` by hand with a JSON file lacking any `"contentType"`-prefixed line vs one with a mismatched value, matching how the existing Markdown collision tests are structured against `CrossKindAddressCollisionAcceptanceTest`/`FilesystemManagedSiteInstallerTest`'s existing collision cases — read one of those first and mirror its exact setup shape).

- [x] 4.5 Add a `PrepareHandlerTest` fixture proving invariant `structuredData` changes (e.g. `publicSearchable` flipping from unset to `true`) force a new candidate requiring review rather than being silently treated as unchanged — this is generic, already-proven machinery (`CandidateSnapshot`/`ReferenceMap` already hash `structuredData` for every kind), so this task should need zero further production changes beyond 4.1, only a fixture proving the existing mechanism already covers curated pages the same way it covers every other kind's invariant metadata.

- [x] 4.6 Run `mvn -f publication-exporter/pom.xml test -Dtest=PublicationContractConformanceTest,FilesystemManagedSiteInstallerTest,CuratedPagePublicationKindTest,PrepareHandlerTest` and confirm all pass, with zero production changes needed beyond 4.1 (if a production change turns out to be needed anywhere else in this task, stop and treat it as a real design gap — the artifact-projection seam from Task 1 plus 4.1's override are expected to already fully cover this).

## Task 5 — End-to-end slice proof

**Files:** `publication-exporter/src/test/java/dev/eugene/publicationexporter/CuratedPageAcceptanceTest.java` (create, modeled on `ConceptAcceptanceTest.java`), `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java` (extend).

- [x] 5.1 Write `CuratedPageAcceptanceTest.aboutPageCompletesAdmissionThroughSiteInstallation()`, following `ConceptAcceptanceTest`'s exact handler-level shape (this project's established convention for end-to-end kind fixtures — not CLI-process-level):

  ```java
  package dev.eugene.publicationexporter;

  import dev.eugene.publicationexporter.admission.PublicationKinds;
  import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
  import dev.eugene.publicationexporter.bridge.BridgeResponse;
  import dev.eugene.publicationexporter.bridge.PublicationIdentity;
  import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
  import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
  import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
  import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
  import dev.eugene.publicationexporter.intake.NoteIntake;
  import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
  import dev.eugene.publicationexporter.prepare.PrepareHandler;
  import dev.eugene.publicationexporter.release.ReleaseOutputStore;
  import dev.eugene.publicationexporter.reference.PublicField;
  import dev.eugene.publicationexporter.site.FilesystemManagedSiteInstaller;
  import dev.eugene.publicationexporter.translation.TranslationWorker;
  import dev.eugene.publicationexporter.vault.VaultAssetReader;
  import dev.eugene.publicationexporter.vault.VaultReader;
  import dev.eugene.publicationexporter.vault.VaultRelativePath;
  import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
  import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
  import org.junit.jupiter.api.Test;
  import org.junit.jupiter.api.io.TempDir;

  import java.nio.file.Files;
  import java.nio.file.Path;
  import java.util.List;
  import java.util.Map;

  import static org.junit.jupiter.api.Assertions.assertEquals;
  import static org.junit.jupiter.api.Assertions.assertTrue;

  class CuratedPageAcceptanceTest {

      private static final String VALID_ABOUT_PAGE = """
              ---
              publish: true
              publicCollection: editorial
              publicContentType: curated_page
              publicId: about
              editorialPage: about
              id: page-about
              title: Обо мне
              publicSearchable: true
              ---
              ## Кратко

              Кратко.

              ## Eyebrow

              Бровь.

              ## Лид

              Лид.

              ## Принципы

              ### Первый

              Принцип.

              ## Колофон

              Колофон.
              """;

      @TempDir
      Path siteRoot;

      @Test
      void aboutPageCompletesAdmissionThroughSiteInstallation() throws Exception {
          VaultRelativePath path = VaultRelativePath.of("editorial/about.md");
          VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ABOUT_PAGE));
          VaultAssetReader vaultAssetReader = VaultAssetReader.createNull();
          NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
          CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
          ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
          TranslationWorker translationWorker = TranslationWorker.createNull(
                  "",
                  List.of(
                          PublicField.of("title", "About Me"),
                          PublicField.of("summary", "Summary."),
                          PublicField.of("eyebrow", "Eyebrow."),
                          PublicField.of("lead", "Lead."),
                          PublicField.of("principles[0].title", "First"),
                          PublicField.of("principles[0].text", "Principle."),
                          PublicField.of("colophon", "Colophon.")));
          WorkflowStatusEditor workflowStatusEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_ABOUT_PAGE));
          PrepareHandler prepareHandler = new PrepareHandler(
                  noteIntake,
                  translationWorker,
                  candidateWorkspace,
                  approvedSnapshotWorkspace,
                  WorkflowStatusEditor.createNull());

          BridgeResponse prepareResponse = prepareHandler.prepare(path, vaultReader, vaultAssetReader);

          assertTrue(prepareResponse.ok(), prepareResponse.diagnostics().toString());
          PublicationIdentity identity = PublicationIdentity.of("editorial", "curated_page", "about");
          assertEquals(identity, prepareResponse.identity());

          MarkReviewedHandler markReviewedHandler = new MarkReviewedHandler(
                  noteIntake,
                  candidateWorkspace,
                  approvedSnapshotWorkspace,
                  workflowStatusEditor);
          BridgeResponse approveResponse = markReviewedHandler.markReviewed(path, vaultReader);

          assertTrue(approveResponse.ok(), approveResponse.diagnostics().toString());
          CandidateSnapshot approved = approvedSnapshotWorkspace.read(identity).orElseThrow();

          ReleaseResult releaseResult = new BuildFromReviewHandler(
                  approvedSnapshotWorkspace,
                  ReleaseOutputStore.createNull()).buildFromReview(identity);

          assertTrue(releaseResult.ok(), releaseResult.message());

          new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);

          String installedRu = Files.readString(siteRoot.resolve("src/data/pages/ru/about.json"));
          String installedEn = Files.readString(siteRoot.resolve("src/data/pages/en/about.json"));
          assertTrue(installedRu.contains("\"title\":\"Обо мне\""));
          assertTrue(installedRu.contains("\"summary\":\"Кратко.\""));
          assertTrue(installedRu.contains("\"searchable\":true"));
          assertTrue(installedEn.contains("\"title\":\"About Me\""));
          assertTrue(installedEn.contains("\"summary\":\"Summary.\""));
          assertTrue(installedEn.contains("\"searchable\":true"));
      }
  }
  ```

  Adjust the exact JSON assertion substrings once Task 4.1's `projectManagedArtifact` override and `CuratedPageJson` (already merged before this task runs) determine the final serialized key names and quoting; the shape above is the expected contract, not a guess to leave unverified.

- [x] 5.2 Run `CuratedPageAcceptanceTest`, fix any assertion/serialization mismatch against the actual output (do not adjust the test to match a wrong output — re-derive the expected JSON from the real `about.json` fixture read during design if in doubt).

- [x] 5.3 Extend `write-publication-contract` CLI acceptance coverage so the emitted contract includes `editorial/curated_page`, with `editorialPage` in its required-fields section (allowed value `about`) and no `description` requirement, matching the established per-kind assertion pattern already used for the other five kinds in that same test.

- [x] 5.4 Run the focused suites touched by this slice first (`AboutPageBodyTest`, `CuratedPagePublicationKindTest`, `PublicationContractConformanceTest`, `FilesystemManagedSiteInstallerTest`, `EnglishCandidateValidatorTest`, `CuratedPageAcceptanceTest`, the extended contract CLI test), then run the complete `mvn -f publication-exporter/pom.xml test` and confirm it is green (baseline was 763 tests; expect that count plus every new test added across Tasks 1-4). Keep the `tasks.md` checkboxes aligned with the verified outcome — check off only tasks whose tests actually pass, not tasks believed complete.
