<!--
Global constraints for every task:
- Module: publication-exporter (Maven, Java 17). Fresh verification command for completion: `mvn -f publication-exporter/pom.xml test`.
- Outside-in TDD: every production change starts from a failing test or acceptance assertion that proves the new concept behavior.
- No generic schema/reflection framework, no new collection/content-type switches in generic orchestration, and no changes to exporter-java/ or site/.
- Follow the current Null*/createNull() testing style. If an interface or constructor used by a fake changes, update the fake in the same task.
- Keep the concept kind as one focused abstraction: final class, composition over inheritance, no type introspection (`instanceof`/casts), and intention-revealing method names (SBPP-BEH-18, Elegant Objects 3.7).
- Every method stays at one level of abstraction (SBPP-BEH-01, Composed Method): a method body reads as a table of contents, not an implementation. Extract a private method for any sub-step that can be named.
- Constructors stay code-free (Elegant Objects 1.3); validation and derivation live in named methods called from `admit()`, not inline in field assignment.
- The bracket-key grouping mechanism added to the site installer is generic YAML-emission infrastructure keyed on key *shape* (`name[i]`, `name[i].sub`), not on `collection`/`contentType` — it must not branch on kind identity (Riel 5.12: replace type-based case analysis with a uniform rule).
- Do not extract a shared `TranslatedListField`/domain abstraction this slice — `ConceptPublicationKind` owns its own flattening and unflattening. Per design.md D1/D2, this is deliberate: concept is the first kind that needs it, and generalizing now would front-run a need no second kind has yet proven (Riel 2.8: one key abstraction per class — do not manufacture a second one speculatively).
-->

## Task 1 — Contract primitives for `relations` and optional `examples`

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/contract/FieldContract.java`, `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/PublicationContractConformanceTest.java`, `publication-exporter/src/test/java/dev/eugene/publicationexporter/contract/FieldContractTest.java` (create if it does not already exist as a focused unit-test home for `FieldContract` factories).

- [x] 1.1 Add `FieldContract.Type.STRUCTURED_LIST` and a named constructor scoped exactly to `relations`' declared shape — an optional list of objects, each requiring non-blank `name` and `relation` string members:

  ```java
  public enum Type { BOOLEAN, STRING, STRING_LIST, STRUCTURED_LIST }

  public static FieldContract nonBlankStructuredList(String name, List<String> memberFields) {
      return new FieldContract(name, Type.STRUCTURED_LIST, null, null, false, List.copyOf(memberFields));
  }
  ```

  Add the new `List<String> structuredMembers` field (constructor parameter, `@JsonProperty("structuredMembers") structuredMembers()` accessor, `equals`/`hashCode`/`toString` updates) following the exact pattern `allowedValues`/`pattern` already use in the class. Do not add a general nested-object contract system — `structuredMembers` only ever holds a flat list of required member-field names for this one shape.
  Write `FieldContractTest` cases first: `nonBlankStructuredList` reports its type, name, and member fields; `equals`/`hashCode` treat two contracts with the same name/type/members as equal and a different member list as unequal.

- [x] 1.2 Extend `PublicationContractConformanceTest`'s shared fixture table with `concepts/concept` cases: a fixture with no `notThis`/`relations`/`examples` (contract and runtime agree it passes), a fixture with a well-formed `relations` entry and `examples` list (agree it passes), and a fixture with a malformed `relations` entry — missing `name`, missing `relation`, or an undeclared member field (agree it fails). These fixtures anticipate `ConceptPublicationKind` from Task 2; write them against the not-yet-existing kind so Task 2 starts from a failing conformance test, per outside-in TDD.

- [x] 1.3 Run `mvn -f publication-exporter/pom.xml test -Dtest=FieldContractTest,PublicationContractConformanceTest` and confirm `FieldContractTest` passes while the new `PublicationContractConformanceTest` concept fixtures fail with "unsupported kind" (expected — `ConceptPublicationKind` does not exist yet).

## Task 2 — `ConceptPublicationKind`: admission, translated-list flattening, and route projection

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/ConceptPublicationKind.java` (create, modeled on `BookPublicationKind.java` and `ClaimPublicationKind.java`), `publication-exporter/src/main/java/dev/eugene/publicationexporter/admission/PublicationKinds.java` (register), `publication-exporter/src/test/java/dev/eugene/publicationexporter/admission/ConceptPublicationKindTest.java` (create).

- [x] 2.1 Write `ConceptPublicationKindTest` first (outside-in): a fixture with valid identity, `title`, `description`, and none of `notThis`/`relations`/`examples` admits with fields `[title, description]`; a fixture adding `notThis` admits with fields `[title, description, notThis]`; a fixture adding two `relations` entries and two `examples` entries admits with fields in the exact order `[title, description, notThis, relations[0].name, relations[0].relation, relations[1].name, relations[1].relation, examples[0], examples[1]]`; a fixture with a `relations` entry missing `name` or `relation`, or an undeclared member field, is blocked with a diagnostic naming `relations`; a fixture with a missing `title`/`description`/`id` is blocked with a diagnostic naming the missing field. Use `MarkdownNote`'s existing test-construction style already used by `BookPublicationKindTest`/`ClaimPublicationKindTest` (no mocking — plain `MarkdownNote` values built through its existing test constructors).

- [x] 2.2 Implement `ConceptPublicationKind`, keeping each step in `admit()` a single named call (Composed Method) and returning early on the first diagnostic-producing guard (SBPP-FMT-05 Guard Clause):

  ```java
  public final class ConceptPublicationKind implements PublicationKind {

      private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
      private static final Set<String> RELATION_MEMBERS = Set.of("name", "relation");

      @Override
      public String collection() {
          return "concepts";
      }

      @Override
      public String contentType() {
          return "concept";
      }

      @Override
      public String routePrefix() {
          return "concepts";
      }

      @Override
      public AdmittedPublication admit(MarkdownNote frontmatter) {
          List<Diagnostic> diagnostics = new ArrayList<>();
          String publicId = requireValidPublicId(frontmatter, diagnostics);
          String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
          String title = requireNonBlank(frontmatter, "title", diagnostics);
          String description = requireNonBlank(frontmatter, "description", diagnostics);
          requireValidRelations(frontmatter, diagnostics);
          requireValidExamples(frontmatter, diagnostics);

          if (!diagnostics.isEmpty()) {
              return AdmittedPublication.blocked(diagnostics);
          }
          return AdmittedPublication.accepted(
                  this,
                  PublicationIdentity.of(collection(), contentType(), publicId),
                  sourceId,
                  translatedFields(frontmatter, title, description),
                  "");
      }
  ```

  `translatedFields(...)` is the flattening step from design.md D2 — one composed method per shape:

  ```java
      private List<PublicField> translatedFields(MarkdownNote frontmatter, String title, String description) {
          List<PublicField> fields = new ArrayList<>();
          fields.add(PublicField.of("title", title));
          fields.add(PublicField.of("description", description));
          appendNotThis(fields, frontmatter);
          appendRelations(fields, frontmatter);
          appendExamples(fields, frontmatter);
          return List.copyOf(fields);
      }

      private void appendNotThis(List<PublicField> fields, MarkdownNote frontmatter) {
          frontmatter.string("notThis")
                  .filter(value -> !value.isBlank())
                  .ifPresent(value -> fields.add(PublicField.of("notThis", value)));
      }

      private void appendRelations(List<PublicField> fields, MarkdownNote frontmatter) {
          List<Map<String, String>> relations = frontmatter.listOfMaps("relations");
          for (int index = 0; index < relations.size(); index++) {
              Map<String, String> relation = relations.get(index);
              fields.add(PublicField.of("relations[" + index + "].name", relation.get("name")));
              fields.add(PublicField.of("relations[" + index + "].relation", relation.get("relation")));
          }
      }

      private void appendExamples(List<PublicField> fields, MarkdownNote frontmatter) {
          List<String> examples = frontmatter.listOfScalars("examples");
          for (int index = 0; index < examples.size(); index++) {
              fields.add(PublicField.of("examples[" + index + "]", examples.get(index)));
          }
      }
  ```

  Validation guards reuse the existing readers exactly as design.md D5 specifies — no new `MarkdownNote` method:

  ```java
      private void requireValidRelations(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
          if (frontmatter.structuredField("relations") == MarkdownNote.StructuredField.NON_LIST) {
              diagnostics.add(Diagnostic.blocking("relations", "concepts/concept relations must be a list."));
              return;
          }
          for (Map<String, String> relation : frontmatter.listOfMaps("relations")) {
              if (!validRelation(relation)) {
                  diagnostics.add(Diagnostic.blocking(
                          "relations",
                          "concepts/concept relations entries require non-blank name and relation, no other fields."));
                  return;
              }
          }
      }

      private boolean validRelation(Map<String, String> relation) {
          return RELATION_MEMBERS.containsAll(relation.keySet())
                  && relation.keySet().containsAll(RELATION_MEMBERS)
                  && nonBlank(relation.get("name"))
                  && nonBlank(relation.get("relation"));
      }

      private void requireValidExamples(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
          if (frontmatter.structuredField("examples") == MarkdownNote.StructuredField.NON_LIST) {
              diagnostics.add(Diagnostic.blocking("examples", "concepts/concept examples must be a list."));
              return;
          }
          if (frontmatter.listOfScalars("examples").stream().anyMatch(example -> !nonBlank(example))) {
              diagnostics.add(Diagnostic.blocking(
                      "examples", "concepts/concept examples entries must be non-blank strings."));
          }
      }

      private static boolean nonBlank(String value) {
          return value != null && !value.isBlank();
      }
  ```

  Complete the class with `contract()` (identity fields required; `notThis` via `FieldContract.nonBlank`, `examples` via `FieldContract.nonBlankStringList` — both placed in the optional-fields list; `relations` via `FieldContract.nonBlankStructuredList("relations", List.of("name", "relation"))`, also optional) and the same `requireValidPublicId`/`requireNonBlank`/`isSlug` private helpers `BookPublicationKind` and `ClaimPublicationKind` already carry — copy their exact bodies for consistency with the sibling kinds, not a shared base class (Riel 5.1: these kinds share an interface, not a specialization relationship — inheritance is not warranted here).

- [x] 2.3 Register the kind: add `new ConceptPublicationKind()` to `PublicationKinds.installed()`'s list, alongside essay/note/claim/book. Update any `InspectPublicationHandlerTest`/`NoteIntake`-adjacent fixture that currently asserts `concepts/concept` is unsupported (mirroring how S17a/S17b/S17c updated the same fixtures when each kind was added).

- [x] 2.4 Run `mvn -f publication-exporter/pom.xml test -Dtest=ConceptPublicationKindTest,FieldContractTest,PublicationContractConformanceTest` and confirm all pass. Run the full `mvn -f publication-exporter/pom.xml test` once to confirm no existing kind's suite regressed.

## Task 3 — Translated-list projection into managed site frontmatter

**Files:** `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java`, `publication-exporter/src/test/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstallerTest.java`.

- [x] 3.1 Write a failing `FilesystemManagedSiteInstallerTest` case first: installing a candidate whose `enFields` include `notThis`, two `relations[i].name`/`relations[i].relation` pairs, and two `examples[i]` entries produces frontmatter containing `notThis: "..."` as a plain scalar line, `relations:` followed by two `- name: "..."` / `relation: "..."` block entries in source order, and `examples:` followed by two `- "..."` block entries in source order — with every other already-existing field (essay/note/claim/book fixtures already in this test file) rendered exactly as before.

- [x] 3.2 Extract a small final helper, colocated in `site/`, whose one responsibility is grouping an ordered `PublicField` list by the bracket-index key convention before rendering (Riel 3.2/4.6 — this keeps `FilesystemManagedSiteInstaller` from growing a second unrelated responsibility inline):

  ```java
  final class BracketIndexedFields {

      private static final Pattern LIST_ITEM = Pattern.compile("^(\\w+)\\[(\\d+)\\](?:\\.(\\w+))?$");

      private BracketIndexedFields() {
      }

      static String render(List<PublicField> fields, Consumer<PublicField> scalarFieldWriter) {
          StringBuilder yaml = new StringBuilder();
          LinkedHashMap<String, LinkedHashMap<Integer, LinkedHashMap<String, String>>> grouped = new LinkedHashMap<>();
          for (PublicField field : fields) {
              Matcher match = LIST_ITEM.matcher(field.key());
              if (!match.matches()) {
                  scalarFieldWriter.accept(field);
                  continue;
              }
              groupListItem(grouped, match, field.value());
          }
          grouped.forEach((name, items) -> appendListBlock(yaml, name, items));
          return yaml.toString();
      }

      private static void groupListItem(
              Map<String, LinkedHashMap<Integer, LinkedHashMap<String, String>>> grouped,
              Matcher match,
              String value) {
          String field = match.group(1);
          int index = Integer.parseInt(match.group(2));
          String subfield = match.group(3);
          grouped.computeIfAbsent(field, ignored -> new LinkedHashMap<>())
                  .computeIfAbsent(index, ignored -> new LinkedHashMap<>())
                  .put(subfield == null ? "" : subfield, value);
      }

      private static void appendListBlock(
              StringBuilder yaml, String field, Map<Integer, LinkedHashMap<String, String>> items) {
          yaml.append(field).append(":\n");
          items.values().forEach(item -> appendListItem(yaml, item));
      }

      private static void appendListItem(StringBuilder yaml, Map<String, String> item) {
          if (item.containsKey("")) {
              yaml.append("  - ").append(YamlScalar.doubleQuoted(item.get(""))).append('\n');
              return;
          }
          boolean first = true;
          for (Map.Entry<String, String> member : item.entrySet()) {
              yaml.append(first ? "  - " : "    ")
                      .append(member.getKey()).append(": ")
                      .append(YamlScalar.doubleQuoted(member.getValue())).append('\n');
              first = false;
          }
      }
  }
  ```

  This groups purely on key *shape* — it has no `import` on any `admission` class and no reference to `collection`/`contentType`, satisfying the generic-infrastructure framing in design.md D3.

- [x] 3.3 Wire it into `frontmatter(...)`, replacing the current unconditional `fields.forEach(...)` line:

  ```java
  yaml.append(BracketIndexedFields.render(fields, field -> appendYamlString(yaml, field.key(), field.value())));
  ```

  (Exact integration point: `publication-exporter/src/main/java/dev/eugene/publicationexporter/site/FilesystemManagedSiteInstaller.java:471`, inside the existing `frontmatter(...)` method — replace the current `fields.forEach(field -> appendYamlString(yaml, field.key(), field.value()));` line with the call above, keeping every surrounding line unchanged.)

- [x] 3.4 Run `mvn -f publication-exporter/pom.xml test -Dtest=FilesystemManagedSiteInstallerTest` and confirm the new case passes and every existing case in that file (essay/note/claim/book) still passes unchanged.

## Task 4 — End-to-end slice proof

**Files:** `publication-exporter/src/test/java/dev/eugene/publicationexporter/ConceptAcceptanceTest.java` (create, modeled on `BibliographyBookAcceptanceTest.java`), `publication-exporter/src/test/java/dev/eugene/publicationexporter/cli/WritePublicationContractCliAcceptanceTest.java` (or equivalent existing contract CLI test — extend, do not duplicate).

- [x] 4.1 Write `ConceptAcceptanceTest.conceptCompletesAdmissionThroughSiteInstallation()`: one RU fixture with a populated `notThis`, two `relations` entries, and two `examples` entries, driven through `admit -> prepare -> approve -> build-from-review -> install-to-site` via the real CLI (matching `BibliographyBookAcceptanceTest`'s shape), using `TranslationWorker.createNull(enBody, enFields)` configured with distinct EN text for every field (not byte-identical to RU, so the test fixture itself models a real translation — per design.md D6, the pipeline does not enforce this, but the acceptance fixture should still demonstrate real translated output). Assert: the RU installed file contains `notThis`/`relations`/`examples` in the site's declared YAML shape with the original Russian text; the EN installed file contains the same shape with the translated English text, same count and order.

- [x] 4.2 Extend the existing `write-publication-contract` CLI acceptance coverage so the emitted contract includes `concepts/concept`, with `notThis` (optional non-blank string), `examples` (optional non-blank string list), and `relations` (optional structured list requiring `name`/`relation`) all appearing in its optional-fields section, sorted consistently with the other kinds' entries.

- [x] 4.3 Run the focused suites touched by this slice first (`ConceptPublicationKindTest`, `FieldContractTest`, `PublicationContractConformanceTest`, `FilesystemManagedSiteInstallerTest`, `ConceptAcceptanceTest`, the extended contract CLI test), then run the complete `mvn -f publication-exporter/pom.xml test` and confirm it is green. Keep the OpenSpec `tasks.md` checkboxes aligned with the verified outcome — check off only tasks whose tests actually pass, not tasks believed complete.
