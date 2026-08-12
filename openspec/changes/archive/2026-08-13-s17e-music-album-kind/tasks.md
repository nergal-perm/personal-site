<!--
Global constraints for every task:
- Module: publication-exporter (Maven, Java 17). Fresh verification command for completion: `mvn -f publication-exporter/pom.xml test`.
- Outside-in TDD: every production change starts from a failing test or acceptance assertion that proves the new album behavior.
- No generic schema/reflection framework, no new collection/content-type switches in generic orchestration, and no changes to exporter-java/ or site/.
- No new `FieldContract.Type` and no new `MarkdownNote` parser method this slice — every field shape (`nonBlank`, `nonBlankStringList`) already exists from S17c/S17d. If a task appears to need a new primitive, stop and check design.md D4 first; this slice was deliberately scoped to need none.
- Do not extract a shared translated-list mechanism this slice (design.md's explicit Non-Goal). `AlbumPublicationKind`'s `listenFor` flattening is a private, near-duplicate copy of `ConceptPublicationKind`'s pattern — copy it, do not import or reference `ConceptPublicationKind` from `AlbumPublicationKind`.
- Follow the current Null*/createNull() testing style. If an interface or constructor used by a fake changes, update the fake in the same task.
- Keep the album kind as one focused abstraction: final class, composition over inheritance, no type introspection (`instanceof`/casts), and intention-revealing method names (SBPP-BEH-18, Elegant Objects 3.7).
- Every method stays at one level of abstraction (SBPP-BEH-01, Composed Method): a method body reads as a table of contents, not an implementation. Extract a private method for any sub-step that can be named.
- Constructors stay code-free (Elegant Objects 1.3); validation and derivation live in named methods called from `admit()`, not inline in field assignment.
-->

## Task 1 — `AlbumPublicationKind`: admission, translated fields, invariant fields, and contract

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/AlbumPublicationKind.java` (create, modeled on `BookPublicationKind.java` for invariant-field structure and `ConceptPublicationKind.java` for the translated-list flattening pattern), `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java` (register), `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/AlbumPublicationKindTest.java` (create), `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/AlbumPublicationKindFixture.java` and `AlbumPublicationKindFixtures.java` (create, matching `BookPublicationKindFixture(s)`/`ConceptPublicationKindFixture(s)`'s established shared-fixture-table shape so `PublicationContractConformanceTest` can reuse them in Task 3).

- [x] 1.1 Write `AlbumPublicationKindTest` first (outside-in): a fixture with valid identity, `title`, `description`, `artist`, `work`, `context`, `association`, and none of the optional fields admits with translated fields `[title, description, context, association]` (no `format`/`care`/`listenFor` entries emitted when absent); a fixture adding `format`, `care`, and two `listenFor` entries admits with translated fields in the exact order `[title, description, context, association, format, care, listenFor[0], listenFor[1]]`; a fixture with a missing/blank `artist`, `work`, `context`, or `association` is blocked with a diagnostic naming that field; a fixture whose `listenFor` or `genreTags` is not a list of non-blank strings is blocked with a diagnostic naming the offending field; a fixture with `genreTags` populated admits successfully (genreTags never appears in the translated-fields list — it is invariant). Use `MarkdownNote`'s existing test-construction style already used by `BookPublicationKindTest`/`ConceptPublicationKindTest` (no mocking).

- [x] 1.2 Implement `AlbumPublicationKind`, following `BookPublicationKind`'s exact shape for invariant fields and `ConceptPublicationKind`'s exact shape for the `listenFor` flattening (Composed Method — each step in `admit()` a single named call, Guard Clause — return early on the first diagnostic-producing check):

  ```java
  public final class AlbumPublicationKind implements PublicationKind {

      private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
      private static final List<String> OPTIONAL_INVARIANT_FIELDS =
              List.of("releaseDate", "streamingUrl", "bandcampEmbedUrl");

      @Override
      public String collection() {
          return "music";
      }

      @Override
      public String contentType() {
          return "album";
      }

      @Override
      public String routePrefix() {
          return "music";
      }

      @Override
      public AdmittedPublication admit(MarkdownNote frontmatter) {
          List<Diagnostic> diagnostics = new ArrayList<>();
          String publicId = requireValidPublicId(frontmatter, diagnostics);
          String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
          String title = requireNonBlank(frontmatter, "title", diagnostics);
          String description = requireNonBlank(frontmatter, "description", diagnostics);
          String artist = requireNonBlank(frontmatter, "artist", diagnostics);
          String work = requireNonBlank(frontmatter, "work", diagnostics);
          String context = requireNonBlank(frontmatter, "context", diagnostics);
          String association = requireNonBlank(frontmatter, "association", diagnostics);
          requireValidOptionalScalar(frontmatter, "format", diagnostics);
          requireValidOptionalScalar(frontmatter, "care", diagnostics);
          requireValidOptionalScalar(frontmatter, "releaseDate", diagnostics);
          requireValidOptionalScalar(frontmatter, "streamingUrl", diagnostics);
          requireValidOptionalScalar(frontmatter, "bandcampEmbedUrl", diagnostics);
          requireValidScalarList(frontmatter, "listenFor", diagnostics);
          requireValidScalarList(frontmatter, "genreTags", diagnostics);

          if (!diagnostics.isEmpty()) {
              return AdmittedPublication.blocked(diagnostics);
          }
          return AdmittedPublication.accepted(
                  this,
                  PublicationIdentity.of(collection(), contentType(), publicId),
                  sourceId,
                  translatedFields(frontmatter, title, description, context, association),
                  structuredDataFrom(frontmatter, artist, work));
      }
  ```

  Translated-field construction, copying `ConceptPublicationKind`'s flattening pattern for `listenFor` verbatim (same synthetic-key convention: `listenFor[0]`, `listenFor[1]`, ...):

  ```java
      private List<PublicField> translatedFields(
              MarkdownNote frontmatter, String title, String description, String context, String association) {
          List<PublicField> fields = new ArrayList<>();
          fields.add(PublicField.of("title", title));
          fields.add(PublicField.of("description", description));
          fields.add(PublicField.of("context", context));
          fields.add(PublicField.of("association", association));
          appendOptionalTranslatedScalar(fields, "format", frontmatter);
          appendOptionalTranslatedScalar(fields, "care", frontmatter);
          appendListenFor(fields, frontmatter);
          return List.copyOf(fields);
      }

      private void appendOptionalTranslatedScalar(List<PublicField> fields, String key, MarkdownNote frontmatter) {
          frontmatter.string(key)
                  .filter(value -> !value.isBlank())
                  .ifPresent(value -> fields.add(PublicField.of(key, value)));
      }

      private void appendListenFor(List<PublicField> fields, MarkdownNote frontmatter) {
          List<String> listenFor = frontmatter.listOfScalars("listenFor");
          for (int index = 0; index < listenFor.size(); index++) {
              fields.add(PublicField.of("listenFor[" + index + "]", listenFor.get(index)));
          }
      }
  ```

  Invariant-field construction, copying `BookPublicationKind`'s exact `structuredDataFrom`/`appendAuthors`/`appendOptionalInvariantField` shape (reusing `genreTags` as the list-of-scalars field, `reviewType` as the one unconditional literal — no frontmatter read for `reviewType`, per design.md D3):

  ```java
      private String structuredDataFrom(MarkdownNote frontmatter, String artist, String work) {
          StringBuilder yaml = new StringBuilder();
          yaml.append("artist: ").append(YamlScalar.doubleQuoted(artist)).append('\n');
          yaml.append("work: ").append(YamlScalar.doubleQuoted(work)).append('\n');
          for (String field : OPTIONAL_INVARIANT_FIELDS) {
              appendOptionalInvariantScalar(yaml, field, frontmatter);
          }
          appendGenreTags(yaml, frontmatter.listOfScalars("genreTags"));
          yaml.append("reviewType: \"album\"\n");
          return yaml.toString();
      }

      private void appendOptionalInvariantScalar(StringBuilder yaml, String key, MarkdownNote frontmatter) {
          frontmatter.string(key)
                  .filter(value -> !value.isBlank())
                  .ifPresent(value -> yaml.append(key).append(": ")
                          .append(YamlScalar.doubleQuoted(value)).append('\n'));
      }

      private void appendGenreTags(StringBuilder yaml, List<String> genreTags) {
          if (genreTags.isEmpty()) {
              return;
          }
          yaml.append("genreTags:\n");
          for (String tag : genreTags) {
              yaml.append("  - ").append(YamlScalar.doubleQuoted(tag)).append('\n');
          }
      }
  ```

  Validation guards, matching `ConceptPublicationKind`'s exact established shape for optional-list malformed-shape detection (`structuredField(...) == POPULATED_LIST && listOfScalars(...).isEmpty()` — S17d's final-review-verified pattern, including treating `NON_LIST` the same as `POPULATED_LIST` for "must satisfy" purposes):

  ```java
      private void requireValidOptionalScalar(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
          if (frontmatter.string(key).filter(value -> !value.isBlank()).isPresent()) {
              return;
          }
          if (frontmatter.structuredField(key) == MarkdownNote.StructuredField.ABSENT) {
              return;
          }
          diagnostics.add(Diagnostic.blocking(
                  key, "music/album optional " + key + " must be a non-blank string when present."));
      }

      private void requireValidScalarList(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
          MarkdownNote.StructuredField shape = frontmatter.structuredField(key);
          if (shape == MarkdownNote.StructuredField.NON_LIST) {
              diagnostics.add(Diagnostic.blocking(key, "music/album " + key + " must be a list."));
              return;
          }
          if (shape == MarkdownNote.StructuredField.POPULATED_LIST
                  && frontmatter.listOfScalars(key).stream().anyMatch(value -> value == null || value.isBlank())) {
              diagnostics.add(Diagnostic.blocking(
                      key, "music/album " + key + " entries must be non-blank strings."));
          }
      }
  ```

  Complete the class with `contract()` (identity fields plus `artist`/`work`/`context`/`association` required via `FieldContract.nonBlank`; `format`/`care`/`releaseDate`/`streamingUrl`/`bandcampEmbedUrl` optional via `FieldContract.nonBlank`; `listenFor`/`genreTags` optional via `FieldContract.nonBlankStringList`) and the same `requireValidPublicId`/`requireNonBlank`/`isSlug` private helpers every sibling kind carries — copy their exact bodies, not a shared base class (Riel 5.1: siblings share an interface, not a specialization relationship).

- [x] 1.3 Create `AlbumPublicationKindFixture`/`AlbumPublicationKindFixtures` in the `admission` package (immutable value + static fixture-table pair, matching `ConceptPublicationKindFixture`/`ConceptPublicationKindFixtures`'s exact shape from S17d): at minimum, an accepted fixture with only the required fields populated, an accepted fixture with every optional field populated (`format`, `care`, two `listenFor` entries, `releaseDate`, two `genreTags`, `streamingUrl`, `bandcampEmbedUrl`), and a blocked fixture with a malformed `listenFor` (a scalar entry instead of a list). Register `new AlbumPublicationKind()` in `PublicationKinds.installed()`.

- [x] 1.4 Run `mvn -f publication-exporter/pom.xml test -Dtest=AlbumPublicationKindTest` and confirm all pass. Run the full `mvn -f publication-exporter/pom.xml test` once to confirm no existing kind's suite regressed.

## Task 2 — Contract conformance and site projection coverage

**Files:** `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java` (extend), `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java` (extend).

- [x] 2.1 Extend `PublicationContractConformanceTest`'s shared fixture table with `music/album` cases, reusing `AlbumPublicationKindFixtures.all()` exactly as the existing `essayContractVerdictAgreesWithFixtureAndRuntimeValidator`-style parameterized tests already reuse each sibling kind's fixture table (copy the `conceptContractVerdictAgreesWithFixtureAndRuntimeValidator` method shape from S17d, renamed for album). No new `fieldSatisfied`/`optionalFieldPresent` logic is expected — every field type album uses (`STRING`, `STRING_LIST`) is already correctly handled by the existing conformance harness, including S17d's final-review-verified `optionalListFieldPresent` fix for empty/`NON_LIST` list fields. If a new gap is found here, treat it as a real finding, not an expected outcome — the harness should already be correct for this slice's field shapes.

- [x] 2.2 Add a `FilesystemManagedSiteInstallerTest` case proving `music/album` frontmatter installs correctly: translated scalars (`context`, `association`, `format`, `care`) render as plain `key: "value"` lines; the translated `listenFor` list renders as a proper YAML list via the existing, unmodified `BracketIndexedFields` mechanism; invariant scalars (`artist`, `work`, `releaseDate`, `streamingUrl`, `bandcampEmbedUrl`) render as plain lines; invariant `genreTags` renders as a proper YAML list; `reviewType: "album"` is present verbatim on both the RU and EN installed files (it is invariant, appended once via `structuredData`, identical on both language files — this is the same mechanism book's invariant metadata already uses, so it needs no new site-installer code, only a test proving it).

- [x] 2.3 Add or update focused `PrepareHandlerTest` fixtures proving `music/album` reuses the current translated-field pipeline with no album-specific exception, and that invariant album metadata (`artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, `bandcampEmbedUrl`) changing after approval forces review instead of silently mirroring the approved snapshot, matching `bibliography/book`'s established `structuredData`-hashing-driven precedent exactly (this is generic, already-proven machinery — `CandidateSnapshot`/`ReferenceMap` already hash `structuredData`, so this task should need no production changes, only fixtures/tests proving the existing mechanism already covers album's invariant fields the same way it covers book's).

- [x] 2.4 Run `mvn -f publication-exporter/pom.xml test -Dtest=PublicationContractConformanceTest,FilesystemManagedSiteInstallerTest,AlbumPublicationKindTest,PrepareHandlerTest` and confirm all pass, with zero changes needed to `FilesystemManagedSiteInstaller.java`, `BracketIndexedFields.java`, `PrepareHandler.java`, or `CandidateSnapshot.java` production code (if a production change turns out to be needed anywhere in this task, stop and treat it as a real design gap to resolve, not silently patch around — this slice's design.md explicitly expects zero changes to any shared, already-generic mechanism).

## Task 3 — End-to-end slice proof

**Files:** `publication-exporter/src/test/java/dev/eugene/publicationexporter/AlbumAcceptanceTest.java` (create, modeled on `ConceptAcceptanceTest.java`), `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java` (extend, do not duplicate).

- [x] 3.1 Write `AlbumAcceptanceTest.albumCompletesAdmissionThroughSiteInstallation()`: one RU fixture with `artist`, `work`, `context`, `association`, `format`, `care`, two `listenFor` entries, `releaseDate`, two `genreTags`, and `streamingUrl`, driven through `admit -> prepare -> approve -> build-from-review -> install-to-site` via the real handler classes (matching `ConceptAcceptanceTest`'s shape — handler-level, not CLI-process-level, per S17d's final-review-confirmed precedent that this is the correct established convention, not a gap), using `TranslationWorker.createNull(enBody, enFields)` configured with distinct EN text for every translated field (not byte-identical to RU). Assert: the RU installed file contains the translated fields in Russian and the invariant fields (`artist`, `work`, `releaseDate`, `genreTags`, `streamingUrl`, `reviewType: "album"`) unchanged; the EN installed file contains the same translated-field structure in English and the identical invariant fields, byte-for-byte the same as the RU file's invariant block.

- [x] 3.2 Extend the existing `write-publication-contract` CLI acceptance coverage so the emitted contract includes `music/album`, with `artist`/`work`/`context`/`association` in its required-fields section (matching the established per-kind required-fields assertion pattern already used for claim/book/concept in this same test) and `format`/`care`/`releaseDate`/`streamingUrl`/`bandcampEmbedUrl`/`listenFor`/`genreTags` in its optional-fields section.

- [x] 3.3 Run the focused suites touched by this slice first (`AlbumPublicationKindTest`, `PublicationContractConformanceTest`, `FilesystemManagedSiteInstallerTest`, `AlbumAcceptanceTest`, the extended contract CLI test), then run the complete `mvn -f publication-exporter/pom.xml test` and confirm it is green. Keep the OpenSpec `tasks.md` checkboxes aligned with the verified outcome — check off only tasks whose tests actually pass, not tasks believed complete.
