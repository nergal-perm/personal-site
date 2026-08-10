<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q -o test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- No new production boundary adapter in this slice — MarkdownNormalizer and MarkdownNormalizationOutcome
  are pure in-process, I/O-free classes.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: prefer small stateless
  collaborators over inheritance, explicit outcome types over exceptions for expected business outcomes,
  guard clauses over nested conditionals, and Composed Method (small, single-purpose private methods)
  throughout. No comments in production code beyond what non-obvious rationale demands — this file's own
  comments are plan scaffolding, not a model for the code you write.
- Full reference documents (read before starting any task): proposal.md, specs/public-content-model/spec.md,
  design.md — all in openspec/changes/s12-protected-markdown-obsidian-comments/.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
-->

## 1. Failing acceptance tests through `prepare` (RED)

All tests in this group go into the existing file
`publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
(package `dev.eugene.publicationexporter.prepare`). It already has the imports, fixtures, and helper
methods (`installApproved(Path reviewRoot)`, `essayWithBody(String body)`) these tests reuse — read the
existing file fully before adding to it; do not duplicate an import or helper that's already there.

- [ ] 1.1 Write a failing test: an Obsidian comment in the source body is absent from the installed
      candidate's RU body.

```java
@Test
void obsidianCommentIsStrippedFromInstalledCandidate() {
    String essay = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Public prose.

            %% private note to self %%

            More public prose.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(path, vaultReader);

    assertTrue(response.ok());
    assertEquals(1, workspace.installed().size());
    String installedRuBody = workspace.installed().get(0).ruBody();
    assertFalse(installedRuBody.contains("%%"));
    assertFalse(installedRuBody.contains("private note to self"));
    assertEquals("# My Essay\n\nPublic prose.\n\n\n\nMore public prose.", installedRuBody);
}
```

  The last assertion pins the exact expected output: the `%% private note to self %%` span is removed
  entirely (not replaced with anything), and the blank lines immediately before and after it are
  untouched — this is what "copy everything before the span, skip the span itself, resume after it"
  produces for a comment that sits alone on its own line between two blank-line-separated paragraphs.

- [ ] 1.2 Write a failing test: link-like text inside an inline code span survives normalization
      byte-for-byte.

```java
@Test
void linkLikeTextInsideInlineCodeSurvivesNormalization() {
    String essay = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Example syntax: `[[Some Note]]` is a wiki-link.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(path, vaultReader);

    assertTrue(response.ok());
    assertEquals("# My Essay\n\nExample syntax: `[[Some Note]]` is a wiki-link.",
            workspace.installed().get(0).ruBody());
}
```

- [ ] 1.3 Write a failing test: link-like and comment-like text inside a fenced code block survives
      normalization byte-for-byte, including a `%%...%%` sequence that would otherwise be stripped as a
      comment.

```java
@Test
void commentAndLinkLikeTextInsideFencedCodeSurviveNormalization() {
    String essay = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Public prose.

            ```markdown
            %% this looks like a comment but is inside a fence %%
            [[This looks like a link]]
            ```

            More public prose.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(path, vaultReader);

    assertTrue(response.ok());
    assertEquals("# My Essay\n\nPublic prose.\n\n```markdown\n"
            + "%% this looks like a comment but is inside a fence %%\n"
            + "[[This looks like a link]]\n```\n\nMore public prose.",
            workspace.installed().get(0).ruBody());
}
```

- [ ] 1.4 Write a failing test: an unclosed Obsidian comment blocks preparation with a diagnostic, installs
      no candidate, and writes no workflow status.

```java
@Test
void unclosedObsidianCommentBlocksPreparationWithoutInstallingACandidate() {
    String essay = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Public prose.

            %% this comment is never closed

            This text is lost if we don't block.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(path, essay));
    NullTranslationWorker worker = new NullTranslationWorker(
            TranslationOutcome.success("EN", "EN title", "EN description."));
    PrepareHandler handler = new PrepareHandler(
            worker, workspace, ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(path, vaultReader);

    assertFalse(response.ok());
    assertEquals("translation_failed", response.status());
    assertEquals("candidate", response.diagnostics().get(0).field());
    assertTrue(worker.requested().isEmpty());
    assertTrue(workspace.installed().isEmpty());
    assertEquals(null, editor.currentValue(path, "workflowStatus"));
}
```

- [ ] 1.5 Write a failing test: an edit that touches only an Obsidian comment (surrounding prose
      unchanged) still counts as "unchanged" against the approved baseline — the skip-optimization fires,
      the translation worker is never invoked, and the mirrored candidate has the comment already stripped.

```java
@Test
void commentOnlyEditStillCountsAsUnchangedAgainstApprovedBaseline() throws Exception {
    Path reviewRoot = temporaryRoot.resolve("comment-only-edit-review");
    PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");
    ApprovedSnapshotWorkspace.create(reviewRoot).install(
            identity, "# My Essay\n\nPublic prose.\n\n\n\nMore prose.", "EN body",
            "My Essay", "EN title", "A valid description.", "EN description",
            ReferenceMap.empty(identity,
                    ContentHash.sha256Hex("# My Essay\n\nPublic prose.\n\n\n\nMore prose."),
                    ContentHash.sha256Hex("EN body"),
                    ContentHash.sha256Hex("My Essay"), ContentHash.sha256Hex("EN title"),
                    ContentHash.sha256Hex("A valid description."), ContentHash.sha256Hex("EN description")));
    String essayWithComment = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: my-essay
            id: 8f2c-my-essay
            title: My Essay
            description: A valid description.
            ---
            # My Essay

            Public prose.

            %% this note was added after approval but changes nothing public %%

            More prose.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewRoot);
    TranslationWorker refusingWorker = (job, ruBody, ruTitle, ruDescription) ->
            fail("Prepare must not invoke the translation worker for a comment-only edit.");
    PrepareHandler handler = new PrepareHandler(
            refusingWorker, candidateWorkspace, ApprovedSnapshotWorkspace.create(reviewRoot),
            WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(
            path, VaultReader.createNull(Map.of(path, essayWithComment)));

    assertTrue(response.ok());
    assertEquals("ready_for_review", response.status());
    CandidateSnapshot candidate = candidateWorkspace.read(identity).orElseThrow();
    assertEquals("# My Essay\n\nPublic prose.\n\n\n\nMore prose.", candidate.ruBody());
}
```

  Note the approved baseline's `ruBody` above has two consecutive blank lines (four `\n` in a row)
  between "Public prose." and "More prose." — this is deliberate, not a typo. The comment sits alone on
  its own line, itself surrounded by one blank line on each side; stripping only the `%%...%%` span
  leaves both of those surrounding blank lines in place (they concatenate, they do not collapse into
  one), so the source's original two blank-line gaps become one four-newline gap once the comment text
  between them is gone. This byte shape was verified by hand-tracing `MarkdownNormalizer`'s algorithm
  against this exact fixture before being written into this task — if your implementation produces a
  different result, the implementation has a bug; do not change this expected value to make a test pass.

- [ ] 1.6 Run the new tests and confirm they fail for the expected reason (missing/incorrect behavior, not
      a compile error or an unrelated failure).

Run: `cd publication-exporter && mvn -q -o test -Dtest=PrepareHandlerTest 2>&1 | tail -100`

Expected: compile error or assertion failures on the five new tests (`MarkdownNormalizer` does not exist
yet, so this will most likely be a compile failure the first time — that's fine, it demonstrates the tests
are wired to real not-yet-existing behavior). Do not proceed to section 2 until you can see exactly why
each new test fails.

## 2. Implement `MarkdownNormalizationOutcome` and `MarkdownNormalizer` (GREEN)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizationOutcome.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizer.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`

**Interfaces:**
- Produces: `MarkdownNormalizationOutcome.normalized(String)`, `MarkdownNormalizationOutcome.unclosedComment(int)`,
  `<T> T resolve(Function<String,T> onNormalized, Function<Integer,T> onUnclosedComment)`,
  `MarkdownNormalizer.normalize(String body)` returning `MarkdownNormalizationOutcome`.
- Consumes (from existing code, do not change these signatures): `NoteIntake.Result` (`accepted()`,
  `identity()`, `body()`, `title()`, `description()`, `sourceHash()`, `diagnostics()`),
  `BridgeResponse.blocked(String, List<Diagnostic>)`, `BridgeResponse.translationFailed(String, Diagnostic)`,
  `Diagnostic.blocking(String, String)`.

- [ ] 2.1 Create `MarkdownNormalizationOutcome.java` — exactly this content:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;

public sealed interface MarkdownNormalizationOutcome permits NormalizedMarkdown, UnclosedObsidianComment {

    static MarkdownNormalizationOutcome normalized(String body) {
        return new NormalizedMarkdown(body);
    }

    static MarkdownNormalizationOutcome unclosedComment(int position) {
        return new UnclosedObsidianComment(position);
    }

    <T> T resolve(
            Function<String, T> onNormalized,
            Function<Integer, T> onUnclosedComment);
}

final class NormalizedMarkdown implements MarkdownNormalizationOutcome {

    private final String body;

    NormalizedMarkdown(String body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public <T> T resolve(Function<String, T> onNormalized, Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onNormalized, "onNormalized");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onNormalized.apply(body);
    }
}

final class UnclosedObsidianComment implements MarkdownNormalizationOutcome {

    private final int position;

    UnclosedObsidianComment(int position) {
        this.position = position;
    }

    @Override
    public <T> T resolve(Function<String, T> onNormalized, Function<Integer, T> onUnclosedComment) {
        Objects.requireNonNull(onNormalized, "onNormalized");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        return onUnclosedComment.apply(position);
    }
}
```

  This mirrors `dev.eugene.publicationexporter.translation.TranslationOutcome`'s existing shape in this
  codebase (sealed interface + `resolve(onSuccess, onFailure)`, package-visible variant classes) — read
  that file first if anything above is unclear.

- [ ] 2.2 Create `MarkdownNormalizer.java` — exactly this content:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarkdownNormalizer {

    private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\r\n]*)$");
    private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \t]*$");
    private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(`+)(?!`).*?\\1(?!`)");
    private static final String COMMENT_MARKER = "%%";

    private MarkdownNormalizer() {
    }

    public static MarkdownNormalizationOutcome normalize(String body) {
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedSpan protectedSpan = nextProtectedSpan(body, cursor);
            int commentStart = body.indexOf(COMMENT_MARKER, cursor);

            if (protectedSpan != null && (commentStart < 0 || protectedSpan.start() <= commentStart)) {
                output.append(body, cursor, protectedSpan.end());
                cursor = protectedSpan.end();
            } else if (commentStart >= 0) {
                int commentEnd = body.indexOf(COMMENT_MARKER, commentStart + COMMENT_MARKER.length());
                if (commentEnd < 0) {
                    return MarkdownNormalizationOutcome.unclosedComment(commentStart);
                }
                output.append(body, cursor, commentStart);
                cursor = commentEnd + COMMENT_MARKER.length();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return MarkdownNormalizationOutcome.normalized(output.toString());
    }

    private static ProtectedSpan nextProtectedSpan(String body, int cursor) {
        ProtectedSpan fenced = fencedSpan(body, cursor);
        ProtectedSpan inline = inlineCodeSpan(body, cursor);
        if (fenced == null) {
            return inline;
        }
        if (inline == null) {
            return fenced;
        }
        return fenced.start() <= inline.start() ? fenced : inline;
    }

    private static ProtectedSpan inlineCodeSpan(String body, int cursor) {
        Matcher matcher = INLINE_CODE.matcher(body);
        return matcher.find(cursor) ? new ProtectedSpan(matcher.start(), matcher.end()) : null;
    }

    private static ProtectedSpan fencedSpan(String body, int cursor) {
        Matcher opening = FENCE_OPEN.matcher(body);
        int searchFrom = cursor;
        while (opening.find(searchFrom)) {
            String fenceChar = opening.group(1);
            String infoString = opening.group(2);
            if (fenceChar.charAt(0) == '`' && infoString.contains("`")) {
                searchFrom = lineEndingEnd(body, opening.end());
                continue;
            }
            int closingSearchFrom = lineEndingEnd(body, opening.end());
            Matcher closing = FENCE_CLOSE.matcher(body);
            while (closing.find(closingSearchFrom)) {
                String closeChar = closing.group(1);
                if (closeChar.charAt(0) == fenceChar.charAt(0) && closeChar.length() >= fenceChar.length()) {
                    return new ProtectedSpan(opening.start(), lineEndingEnd(body, closing.end()));
                }
                closingSearchFrom = lineEndingEnd(body, closing.end());
            }
            return new ProtectedSpan(opening.start(), body.length());
        }
        return null;
    }

    private static int lineEndingEnd(String body, int position) {
        if (body.startsWith("\r\n", position)) {
            return position + 2;
        }
        if (position < body.length() && (body.charAt(position) == '\r' || body.charAt(position) == '\n')) {
            return position + 1;
        }
        return position;
    }

    private record ProtectedSpan(int start, int end) {
    }
}
```

  Note this class deliberately has no shared `Kind` enum across fenced/inline/comment (see `design.md`'s
  D4) — fenced and inline code are structurally identical from the caller's perspective (`ProtectedSpan`,
  copy verbatim), and the Obsidian comment case is handled entirely separately in `normalize(...)`'s main
  loop since it is the only span kind that gets dropped rather than copied through.

- [ ] 2.3 Wire `MarkdownNormalizer` into `PrepareHandler.prepare(...)`. Read the current file first — it
      was modified earlier today by a different change (`dec-20260810-43c363ff`, the approved-snapshot
      mirroring fix), so match against the actual current content, not an assumption of what it looks
      like. Apply this transformation:

  Change:
```java
    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        Optional<CandidateSnapshot> unchangedApproved;
        try {
```
  to:
```java
    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        return MarkdownNormalizer.normalize(intake.body()).resolve(
                normalizedBody -> prepareNormalizedEssay(notePath, vaultReader, intake, normalizedBody),
                position -> unclosedCommentFailure(position));
    }

    private BridgeResponse prepareNormalizedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake, String normalizedBody) {
        Optional<CandidateSnapshot> unchangedApproved;
        try {
```
  Exactly two remaining lines inside what is now `prepareNormalizedEssay` reference `intake.body()` and
  must be changed to `normalizedBody` instead:
```java
            unchangedApproved = matchingApprovedBaseline(
                    intake.identity(), intake.body(), intake.title(), intake.description());
```
  becomes
```java
            unchangedApproved = matchingApprovedBaseline(
                    intake.identity(), normalizedBody, intake.title(), intake.description());
```
  and
```java
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                    intake.sourceHash(), intake.body(), intake.title(), intake.description());
```
  becomes
```java
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                    intake.sourceHash(), normalizedBody, intake.title(), intake.description());
```
  `intake.title()` and `intake.description()` are unaffected on both lines — they are frontmatter
  scalars, not Markdown prose. Nothing else in the method body changes: the rest of the old `prepare`
  method (the `if (unchangedApproved.isPresent())` block through the `installLock`/`finally` block) moves
  into `prepareNormalizedEssay` verbatim except for these two substitutions, keeping its existing closing
  braces exactly as they are today.

  Add a new private static method, next to the other small `BridgeResponse`-building helpers at the
  bottom of the class (near `approvedLookupFailure`):
```java
    private static BridgeResponse unclosedCommentFailure(int position) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Obsidian comment starting at position " + position + " is never closed."));
    }
```

  Add the import `import dev.eugene.publicationexporter.prepare.MarkdownNormalizer;`? — no: `MarkdownNormalizer`
  and `PrepareHandler` are both in package `dev.eugene.publicationexporter.prepare`, so no new import is
  needed for it. Do not add an import that isn't required.

- [ ] 2.4 Run the full `PrepareHandlerTest` class and confirm all tests pass, including the five written in
      section 1.

Run: `cd publication-exporter && mvn -q -o test -Dtest=PrepareHandlerTest 2>&1 | tail -100`

Expected: no output (quiet-mode success) or, if there is output, zero failures/errors. Check
`target/surefire-reports/dev.eugene.publicationexporter.prepare.PrepareHandlerTest.txt` for the exact
pass count if unsure.

- [ ] 2.5 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizationOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizer.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(exporter): strip Obsidian comments and protect code spans in prepare"
```

## 3. Narrow unit tests for genuinely combinatorial scanner logic

`design.md`'s Risks section names two specific correctness hazards that are easy to get subtly wrong and
are not exercised by section 1's acceptance-level fixtures: fence character/length matching (a
too-short closing fence must be skipped in favor of a real closer) and backtick-run-length matching for
inline code (a shorter or longer backtick run inside must not falsely close the span). Per this project's
outside-in discipline, these are exactly the "genuinely combinatorial protected-region cases" that justify
narrow unit tests against `MarkdownNormalizer` directly, instead of only through `prepare`.

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizerTest.java`

**Interfaces:**
- Consumes: `MarkdownNormalizer.normalize(String)` returning `MarkdownNormalizationOutcome` (from section 2).

- [ ] 3.1 Write and verify a test proving a too-short closing fence is skipped in favor of the real
      closer (a 3-backtick line inside a 4-backtick-fenced block does not close it).

```java
package dev.eugene.publicationexporter.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.concurrent.TimeUnit;

final class MarkdownNormalizerTest {

    private static String normalizedBodyOrFail(String body) {
        return MarkdownNormalizer.normalize(body).resolve(
                normalized -> normalized,
                position -> fail("Expected a normalized result but got an unclosed comment at " + position));
    }

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void tooShortClosingFenceIsSkippedInFavorOfTheRealCloser() {
        String body = "Visible.\n\n````\nStill hidden.\n```\n````\n\nAfter.";

        assertEquals(body, normalizedBodyOrFail(body));
    }
```

  This pins the fenced-code span as covering the entire `` ```` ... ```` `` block (the inner 3-backtick
  line does not close a 4-backtick-opened fence), so the whole thing — including the inner short fence
  line — is copied through unchanged, and nothing inside it is treated as a candidate comment span. The
  `@Timeout` guards against a pathological backtracking scan if this regex/loop combination is ever
  changed to something with exponential worst-case behavior.

- [ ] 3.2 Write and verify a test proving backtick-run-length matching for inline code: a single backtick
      inside a double-backtick-delimited span does not close it.

```java
    @Test
    void shorterBacktickRunInsideDoesNotFalselyCloseInlineCode() {
        String body = "``code with a lone ` backtick inside``  after.";

        assertEquals(body, normalizedBodyOrFail(body));
    }
```

- [ ] 3.3 Write and verify a test proving CRLF line endings are recognized correctly for fenced code (the
      legacy oracle's evidence flagged this as a real correctness hazard, not a hypothetical one).

```java
    @Test
    void fencedCodeIsRecognisedWithCarriageReturnLineEndings() {
        String body = "Visible.\r\n\r\n~~~\r\n%% not a comment, this is code %%\r\n~~~\r\n\r\nAfter.";

        assertEquals(body, normalizedBodyOrFail(body));
    }
```

- [ ] 3.4 Write and verify a test proving an unclosed fence protects through to end-of-body without
      blocking (contrast this with the unclosed-*comment* case, which does block — see task 1.4). No
      content is lost either way: an unclosed fence just means everything after it is treated as code, a
      self-evident and immediately visible formatting artifact, not silent data loss.

```java
    @Test
    void unclosedFenceProtectsThroughEndOfBodyWithoutBlocking() {
        String body = "Visible.\n\n```\n%% not a comment, this is unclosed code %%";

        assertEquals(body, normalizedBodyOrFail(body));
    }

    @Test
    void unclosedObsidianCommentReturnsItsStartPosition() {
        String body = "Visible. %% never closed";

        int position = MarkdownNormalizer.normalize(body).resolve(
                normalized -> fail("Expected an unclosed-comment outcome but normalization succeeded: " + normalized),
                reportedPosition -> reportedPosition);

        assertEquals(body.indexOf("%%"), position);
    }
}
```

  (The closing `}` above ends the `MarkdownNormalizerTest` class — this is the last test in the file.)

- [ ] 3.5 Run the new test class and the full `PrepareHandlerTest` class together, confirm both green.

Run: `cd publication-exporter && mvn -q -o test -Dtest=MarkdownNormalizerTest,PrepareHandlerTest 2>&1 | tail -150`

Expected: no failures. If `tooShortClosingFenceIsSkippedInFavorOfTheRealCloser` times out, the fence-closing
loop in `MarkdownNormalizer.fencedSpan` is not advancing `closingSearchFrom` correctly on a non-matching
candidate line — re-check that branch against task 2.2's exact code before changing anything else.

- [ ] 3.6 Commit.

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizerTest.java
git commit -m "test(exporter): add narrow MarkdownNormalizer coverage for fence and backtick edge cases"
```

## 4. Full-suite verification

- [ ] 4.1 Run the complete `publication-exporter` test suite and confirm every test passes (the baseline
      before this change was 484 tests, 0 failures — this slice adds 5 acceptance tests plus 6 unit tests,
      so expect 495 tests, 0 failures, 0 errors, 0 skipped).

```bash
cd publication-exporter && mvn -q -o test 2>&1 | tail -150
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[ ,]+' '{tests+=$3; fail+=$5; err+=$7; skip+=$9} END {print "Tests run:", tests, "Failures:", fail, "Errors:", err, "Skipped:", skip}'
```

- [ ] 4.2 Run the OpenSpec strict validation for this change and confirm it passes.

```bash
cd /Users/eugene/Dev/personal-site && openspec validate "s12-protected-markdown-obsidian-comments" --strict
```

- [ ] 4.3 Refresh the graphify code graph (project convention after any code change).

```bash
graphify update .
```

Do not archive the OpenSpec change or touch Haft artifacts from this task list — those steps are owned by
the orchestrating session, not by an implementer subagent.
