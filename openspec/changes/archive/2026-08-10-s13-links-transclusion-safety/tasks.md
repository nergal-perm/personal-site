<!--
Global constraints (apply to every task below):
- Module: publication-exporter (Maven, Java 17). Build/test: `mvn -q -o test` from publication-exporter/.
- Outside-in TDD: write the failing test before any production code (openspec/implementation-plan.md's
  discipline). Do not write production code for a behavior that has no failing test demonstrating it first.
- No new production boundary adapter in this slice — LinkResolver, LinkResolutionOutcome, PublicNoteIndex,
  and ProtectedRegionScanner are pure in-process, I/O-free classes; PublicNoteIndex.from(...) reuses the
  already-shipped VaultReader port, it does not add a new one.
- Governed by /nullables, /applying-sbpp, /elegant-objects, /oo-design-guide: prefer small stateless
  collaborators over inheritance, explicit sealed outcome types over exceptions for expected business
  outcomes, guard clauses over nested conditionals, Composed Method (small, single-purpose private
  methods) throughout, package-private visibility by default (public only where a different package
  needs the type), and never a null return — every "maybe absent" result is Optional or a sealed outcome.
  No comments in production code beyond what non-obvious rationale demands — this file's own comments
  are plan scaffolding, not a model for the code you write.
- Full reference documents (read before starting any task): proposal.md, specs/public-content-model/spec.md,
  design.md — all in openspec/changes/s13-links-transclusion-safety/. design.md's D1-D6 map directly onto
  the classes this file creates; read it first if anything below is unclear on *why*, not just *what*.
- Never modify exporter-java/ — it is a read-only compatibility oracle, not a code donor.
- One important design.md correction folded into this file already: the D4 snippet in design.md's first
  draft let a filename-stem collision resolve to whichever note the enumeration happened to reach last
  (last-write-wins), which silently contradicts PCM-03 — an ambiguous target must fall back to the safe
  label, not resolve to an arbitrary winner. design.md has since been corrected in place; the code below
  implements the corrected (collision-tracked, both-removed) version. Do not reintroduce last-write-wins.
-->

## 1. Failing acceptance tests through `prepare` (RED)

All tests in this group go into the existing file
`publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java`
(package `dev.eugene.publicationexporter.prepare`). Read the existing file fully before adding to it —
it already has the imports, fixtures, and helper types (`NullCandidateWorkspace`, `NullWorkflowStatusEditor`,
`NullTranslationWorker`) these tests reuse; do not duplicate an import or helper that's already there.

- [x] 1.1 Write a failing test: preparing an essay that links to one public note and one private note
      resolves the public link to its route and renders the private link as a safe plain-text label, in
      the same body — this is the "three-note in-memory vault" the plan's S13 acceptance boundary names
      (the essay being prepared, plus its two link targets).

```java
@Test
void publicLinkResolvesToRouteWhilePrivateLinkBecomesASafeLabel() {
    String publicTarget = """
            ---
            publish: true
            publicCollection: blog
            publicContentType: essay
            publicId: notes-on-time
            id: 91aa-notes-on-time
            title: Заметка о времени
            description: A valid description.
            ---
            # Заметка о времени

            Public prose.""";
    String privateTarget = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft
            id: 4c1b-draft
            title: Черновик
            description: A valid description.
            ---
            # Черновик

            Not yet public.""";
    String referrer = """
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

            Смотрите также [[Заметка о времени]] и [[Черновик]].""";
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
    VaultRelativePath publicTargetPath = VaultRelativePath.of("blog/Заметка о времени.md");
    VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            referrerPath, referrer,
            publicTargetPath, publicTarget,
            privateTargetPath, privateTarget));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(referrerPath, vaultReader);

    assertTrue(response.ok());
    assertEquals(
            "# My Essay\n\nСмотрите также [Заметка о времени](/essays/notes-on-time/) и Черновик.",
            workspace.installed().get(0).ruBody());
}
```

  The public target's file is named `Заметка о времени.md` — matching is by vault-relative filename stem
  (basename without `.md`), not by frontmatter `title`, per design.md D4. The private target is present
  in the vault but has `publish: false`, so it never reaches `PublicNoteIndex` at all (design.md D4:
  `listPublishCandidates()` already filters to `publish: true` before this slice's code ever sees a path).

- [x] 1.2 Write a failing test: transcluding (`![[Target]]`) a private note blocks preparation before any
      candidate is installed or workflow status is written — mirroring task 1.4's shape in this same file
      for the unclosed-comment case (S12), since both are "cannot produce a valid candidate from this
      input" failures that must short-circuit before translation is ever attempted.

```java
@Test
void privateTransclusionBlocksPreparationWithoutInstallingACandidate() {
    String privateTarget = """
            ---
            publish: false
            publicCollection: blog
            publicContentType: essay
            publicId: draft
            id: 4c1b-draft
            title: Черновик
            description: A valid description.
            ---
            # Черновик

            Not yet public.""";
    String referrer = """
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

            ![[Черновик]]""";
    VaultRelativePath referrerPath = VaultRelativePath.of("blog/my-essay.md");
    VaultRelativePath privateTargetPath = VaultRelativePath.of("blog/Черновик.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(
            referrerPath, referrer, privateTargetPath, privateTarget));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    NullWorkflowStatusEditor editor = new NullWorkflowStatusEditor(Map.of(referrerPath, referrer));
    NullTranslationWorker worker = new NullTranslationWorker(
            TranslationOutcome.success("EN", "EN title", "EN description."));
    PrepareHandler handler = new PrepareHandler(
            worker, workspace, ApprovedSnapshotWorkspace.createNull(), editor);

    BridgeResponse response = handler.prepare(referrerPath, vaultReader);

    assertFalse(response.ok());
    assertEquals("translation_failed", response.status());
    assertEquals("candidate", response.diagnostics().get(0).field());
    assertTrue(worker.requested().isEmpty());
    assertTrue(workspace.installed().isEmpty());
    assertEquals(null, editor.currentValue(referrerPath, "workflowStatus"));
}
```

  `worker.requested().isEmpty()` is the key assertion proving link resolution runs, and blocks, *before*
  translation — matching design.md D6's composition order (`MarkdownNormalizer.normalize(...).resolve(...
  LinkResolver.resolve(...)...)` sits ahead of `TranslationJob`/`translateCandidate` in `prepare()`).

- [x] 1.3 Write a failing test: an embed whose target has a recognized publishable-asset extension is left
      completely untouched — neither resolved to a route nor blocked as a transclusion.

```java
@Test
void assetEmbedIsLeftUntouchedByLinkResolution() {
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

            ![[diagram.png]]

            More prose.""";
    VaultRelativePath path = VaultRelativePath.of("blog/my-essay.md");
    VaultReader vaultReader = VaultReader.createNull(Map.of(path, essay));
    NullCandidateWorkspace workspace = new NullCandidateWorkspace();
    PrepareHandler handler = new PrepareHandler(
            TranslationWorker.createNull("Translated body", "Translated title", "Translated description."),
            workspace, ApprovedSnapshotWorkspace.createNull(), WorkflowStatusEditor.createNull());

    BridgeResponse response = handler.prepare(path, vaultReader);

    assertTrue(response.ok());
    assertEquals("# My Essay\n\n![[diagram.png]]\n\nMore prose.", workspace.installed().get(0).ruBody());
}
```

- [x] 1.4 Run the new tests and confirm they fail for the expected reason (missing behavior or a compile
      error because `LinkResolver`/`PublicNoteIndex`/`LinkResolutionOutcome` do not exist yet — not an
      unrelated failure).

Run: `cd publication-exporter && mvn -q -o test -Dtest=PrepareHandlerTest 2>&1 | tail -100`

Do not proceed to section 2 until you can see exactly why each of the three new tests fails. Also note
(no action needed yet): the existing test `commentAndLinkLikeTextInsideFencedCodeSurviveNormalization` in
this same file already asserts that literal `[[This looks like a link]]` inside a fenced code block
survives byte-for-byte — that test is this slice's regression guard for protected-region correctness once
`LinkResolver` exists; if section 3's implementation ever breaks protected-region handling, that
pre-existing test (not a new one) is what will turn red.

## 2. Extract `ProtectedRegionScanner` from `MarkdownNormalizer` (REFACTOR — stays green throughout)

Per design.md D1/D2: `LinkResolver` needs the exact same fenced-code/inline-code detection
`MarkdownNormalizer` already has, so it can skip past protected regions without duplicating the
fence/backtick regexes. Extract that detection into a shared, package-private primitive *before* writing
any link-resolution logic. This step changes no observable behavior — run the full suite before and after
to prove it.

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/ProtectedRegionScanner.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizer.java`

- [x] 2.1 Read the current `MarkdownNormalizer.java` in full first — it may have evolved since this task
      list was written; match the refactor against its actual current content, not an assumption. Create
      `ProtectedRegionScanner.java` by moving `nextProtectedSpan`, `inlineCodeSpan`, `fencedSpan`,
      `invalidFenceOpening`, `fenceSpan`, `closingFenceEnd`, `matchingFence`, `lineEndingEnd`, the
      `ProtectedSpan` record, and the `FENCE_OPEN`/`FENCE_CLOSE`/`INLINE_CODE` patterns out of
      `MarkdownNormalizer` verbatim (same bodies, same regexes — this is a pure move, not a rewrite):

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProtectedRegionScanner {

    private static final Pattern FENCE_OPEN = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})([^\\r\\n]*)$");
    private static final Pattern FENCE_CLOSE = Pattern.compile("(?m)^ {0,3}(`{3,}|~{3,})[ \\t]*$");
    private static final Pattern INLINE_CODE = Pattern.compile("(?s)(?<!\\\\)(?<!`)(`+)(?!`).*?(?<!`)\\1(?!`)");

    private ProtectedRegionScanner() {
    }

    static ProtectedSpan nextProtectedSpan(String body, int cursor) {
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
            if (invalidFenceOpening(fenceChar, infoString)) {
                searchFrom = lineEndingEnd(body, opening.end());
                continue;
            }
            return fenceSpan(body, opening, fenceChar);
        }
        return null;
    }

    private static boolean invalidFenceOpening(String fenceChar, String infoString) {
        return fenceChar.charAt(0) == '`' && infoString.contains("`");
    }

    private static ProtectedSpan fenceSpan(String body, Matcher opening, String fenceChar) {
        Optional<Integer> closingEnd = closingFenceEnd(body, opening, fenceChar);
        return new ProtectedSpan(opening.start(), closingEnd.orElse(body.length()));
    }

    private static Optional<Integer> closingFenceEnd(String body, Matcher opening, String fenceChar) {
        int searchFrom = lineEndingEnd(body, opening.end());
        Matcher closing = FENCE_CLOSE.matcher(body);
        while (closing.find(searchFrom)) {
            if (matchingFence(fenceChar, closing.group(1))) {
                return Optional.of(lineEndingEnd(body, closing.end()));
            }
            searchFrom = lineEndingEnd(body, closing.end());
        }
        return Optional.empty();
    }

    private static boolean matchingFence(String opening, String closing) {
        return closing.charAt(0) == opening.charAt(0) && closing.length() >= opening.length();
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

    record ProtectedSpan(int start, int end) {
    }
}
```

- [x] 2.2 Update `MarkdownNormalizer.java` so it delegates to `ProtectedRegionScanner` instead of holding
      its own copy of the fence/inline-code logic. Everything about comment-stripping (`normalize`,
      `nextCommentStart`, `protectedSpanBeforeComment`, `copyProtectedSpan`, `commentEnd`, `skipComment`,
      the `COMMENT_MARKER` constant) stays exactly as it is today — only the type of `protectedSpan` and
      the call that produces it change:

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Optional;

public final class MarkdownNormalizer {

    private static final String COMMENT_MARKER = "%%";

    private MarkdownNormalizer() {
    }

    public static MarkdownNormalizationOutcome normalize(String body) {
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            int commentStart = nextCommentStart(body, cursor);
            if (protectedSpanBeforeComment(protectedSpan, commentStart)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (commentStart >= 0) {
                Optional<Integer> commentEnd = commentEnd(body, commentStart);
                if (commentEnd.isEmpty()) {
                    return MarkdownNormalizationOutcome.unclosedComment(commentStart);
                }
                cursor = skipComment(body, output, cursor, commentStart, commentEnd.get());
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return MarkdownNormalizationOutcome.normalized(output.toString());
    }

    private static int nextCommentStart(String body, int cursor) {
        return body.indexOf(COMMENT_MARKER, cursor);
    }

    private static boolean protectedSpanBeforeComment(
            ProtectedRegionScanner.ProtectedSpan protectedSpan, int commentStart) {
        return protectedSpan != null && (commentStart < 0 || protectedSpan.start() <= commentStart);
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<Integer> commentEnd(String body, int commentStart) {
        int closingMarker = body.indexOf(COMMENT_MARKER, commentStart + COMMENT_MARKER.length());
        return closingMarker < 0 ? Optional.empty() : Optional.of(closingMarker);
    }

    private static int skipComment(
            String body, StringBuilder output, int cursor, int commentStart, int commentEnd) {
        output.append(body, cursor, commentStart);
        return commentEnd + COMMENT_MARKER.length();
    }
}
```

  No import of `ProtectedRegionScanner` is needed — it is in the same package.

- [x] 2.3 Run the full test suite and confirm it is exactly as green as it was before this refactor
      (the three new failing tests from section 1 are still failing — for the same reason as before, since
      `LinkResolver` still doesn't exist — everything else must be unchanged).

Run: `cd publication-exporter && mvn -q -o test 2>&1 | tail -100`

Expected: only the three tests from section 1 fail (compile error, since `LinkResolver` is referenced by
nothing yet at this point — actually at this point in the sequence they still fail the *same way* as task
1.4 recorded, since nothing about section 1's tests references `LinkResolver` directly, only `prepare()`'s
observable behavior). If anything *else* newly fails, the extraction changed behavior — stop and compare
`ProtectedRegionScanner`'s moved code against `MarkdownNormalizer`'s original byte-for-byte before
continuing; do not proceed with a behavior change hiding inside a step labeled "refactor."

- [x] 2.4 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/ProtectedRegionScanner.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/MarkdownNormalizer.java
git commit -m "refactor(exporter): extract ProtectedRegionScanner from MarkdownNormalizer"
```

## 3. Implement `LinkResolutionOutcome`, `PublicNoteIndex`, `LinkResolver`, and wire into `PrepareHandler` (GREEN)

**Files:**
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolutionOutcome.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java`
- Create: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/SourceFreshnessOutcome.java`
- Modify: `publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java`

- [x] 3.1 Create `LinkResolutionOutcome.java` — mirrors `MarkdownNormalizationOutcome`'s exact shape
      (design.md D3): a private/unresolved/ambiguous link is a *successful* resolution (the body still
      comes out, just with a plain label), so only a blocked transclusion is the terminal branch.

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;

public sealed interface LinkResolutionOutcome permits ResolvedLinks, BlockedTransclusion {

    static LinkResolutionOutcome resolved(String body) {
        return new ResolvedLinks(body);
    }

    static LinkResolutionOutcome blockedTransclusion(String target) {
        return new BlockedTransclusion(target);
    }

    <T> T resolve(
            Function<String, T> onResolved,
            Function<String, T> onBlockedTransclusion);
}

final class ResolvedLinks implements LinkResolutionOutcome {

    private final String body;

    ResolvedLinks(String body) {
        this.body = Objects.requireNonNull(body, "body");
    }

    @Override
    public <T> T resolve(Function<String, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onResolved.apply(body);
    }
}

final class BlockedTransclusion implements LinkResolutionOutcome {

    private final String target;

    BlockedTransclusion(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(Function<String, T> onResolved, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onResolved, "onResolved");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onBlockedTransclusion.apply(target);
    }
}
```

- [x] 3.2 Create `PublicNoteIndex.java` (design.md D4, with the ambiguity-collision fix folded in — see
      this file's header note). The constructor is package-private, not private: it is the direct test
      seam `LinkResolverTest` (section 4) uses to build a known-notes fixture without a full `VaultReader`,
      the same role a plain constructor plays throughout this codebase's nullable-style collaborators.

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

    static PublicNoteIndex from(VaultReader vaultReader) {
        Objects.requireNonNull(vaultReader, "vaultReader");
        Map<String, String> routes = new LinkedHashMap<>();
        Set<String> ambiguousStems = new HashSet<>();
        for (VaultRelativePath candidate : vaultReader.listPublishCandidates()) {
            registerIfAdmitted(vaultReader, candidate, routes, ambiguousStems);
        }
        ambiguousStems.forEach(routes::remove);
        return new PublicNoteIndex(routes);
    }

    Optional<String> routeFor(String linkTarget) {
        return Optional.ofNullable(routesByFilenameStem.get(linkTarget));
    }

    private static void registerIfAdmitted(
            VaultReader vaultReader, VaultRelativePath candidate,
            Map<String, String> routes, Set<String> ambiguousStems) {
        NoteIntake.Result intake = new NoteIntake().admit(candidate, vaultReader);
        if (!intake.accepted()) {
            return;
        }
        String stem = filenameStem(candidate);
        if (routes.containsKey(stem)) {
            ambiguousStems.add(stem);
            return;
        }
        routes.put(stem, routeFor(intake.identity()));
    }

    private static String routeFor(PublicationIdentity identity) {
        return "/essays/" + identity.publicId() + "/";
    }

    private static String filenameStem(VaultRelativePath path) {
        String value = path.value();
        int lastSlash = value.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? value.substring(lastSlash + 1) : value;
        return fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
    }
}
```

  `routeFor(PublicationIdentity)` has no kind switch — see design.md D4's note: `NoteIntake` only ever
  admits essays today, so a `default` branch for another kind would be unreachable dead code until S17
  introduces a second kind. Do not add a switch here speculatively.

- [x] 3.3 Create `LinkResolver.java` (design.md D3/D6): the same leftmost-match-among-candidates,
      copy-through, repeat shape `MarkdownNormalizer.normalize` uses, but driven by `ProtectedRegionScanner`
      plus a wikilink/embed candidate instead of a comment candidate.

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkResolver {

    private static final Pattern WIKILINK =
            Pattern.compile("(!?)\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?]]");
    private static final Set<String> ASSET_EXTENSIONS =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".mp3", ".mp4");

    private LinkResolver() {
    }

    public static LinkResolutionOutcome resolve(String body, PublicNoteIndex knownNotes) {
        StringBuilder output = new StringBuilder(body.length());
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            Matcher link = nextLink(body, cursor);
            if (protectedSpanBeforeLink(protectedSpan, link)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (link != null) {
                Optional<String> blockedTarget = appendLink(body, output, cursor, link, knownNotes);
                if (blockedTarget.isPresent()) {
                    return LinkResolutionOutcome.blockedTransclusion(blockedTarget.get());
                }
                cursor = link.end();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return LinkResolutionOutcome.resolved(output.toString());
    }

    private static Matcher nextLink(String body, int cursor) {
        Matcher matcher = WIKILINK.matcher(body);
        return matcher.find(cursor) ? matcher : null;
    }

    private static boolean protectedSpanBeforeLink(ProtectedRegionScanner.ProtectedSpan protectedSpan, Matcher link) {
        return protectedSpan != null && (link == null || protectedSpan.start() <= link.start());
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<String> appendLink(
            String body, StringBuilder output, int cursor, Matcher link, PublicNoteIndex knownNotes) {
        output.append(body, cursor, link.start());
        boolean isEmbed = !link.group(1).isEmpty();
        String target = link.group(2).strip();
        String label = labelFor(link, target);
        if (isEmbed && isAssetTarget(target)) {
            output.append(link.group());
            return Optional.empty();
        }
        Optional<String> route = knownNotes.routeFor(target);
        if (route.isPresent()) {
            output.append('[').append(label).append("](").append(route.get()).append(')');
            return Optional.empty();
        }
        if (isEmbed) {
            return Optional.of(target);
        }
        output.append(label);
        return Optional.empty();
    }

    private static String labelFor(Matcher link, String target) {
        String alias = link.group(3);
        return alias != null ? alias.strip() : target;
    }

    private static boolean isAssetTarget(String target) {
        String lowercaseTarget = target.toLowerCase(Locale.ROOT);
        return ASSET_EXTENSIONS.stream().anyMatch(lowercaseTarget::endsWith);
    }
}
```

  Note what `appendLink` does for an embed whose target *does* resolve to a public note: it takes the
  same `route.isPresent()` branch a plain link would, producing `[label](route)` rather than inlined
  content. This slice does not implement real content transclusion (fetching and splicing in the target
  note's body) for anyone — public or private — that machinery is not hinted at anywhere in proposal.md or
  design.md's Goals. Degrading a public-target embed to a link is strictly safer than either leaving the
  raw `![[...]]` syntax visible in public output or attempting to fabricate inlined content this slice was
  never asked to produce. Section 4 has a dedicated test for this exact case — do not treat it as
  unspecified; it is a deliberate, minimal choice, not an oversight.

- [x] 3.4 Extend `SourceFreshnessOutcome.java` with a fourth branch so the freshness re-check (which
      re-runs `MarkdownNormalizer` today) can also re-run `LinkResolver` and report a newly-discovered
      blocked transclusion with its own diagnostic — the same "give a re-discovered terminal problem its
      own branch, don't squash it into `stale`" precedent the existing `unclosedComment` branch already
      set. Read the current file first (reproduced in full below as of this task list's writing — confirm
      it still matches before editing):

```java
package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

sealed interface SourceFreshnessOutcome
        permits MatchingSource, StaleSource, UnclosedSourceComment, BlockedTransclusionSource {

    static SourceFreshnessOutcome matches(String sourceHash) {
        return new MatchingSource(sourceHash);
    }

    static SourceFreshnessOutcome stale() {
        return new StaleSource();
    }

    static SourceFreshnessOutcome unclosedComment(int position) {
        return new UnclosedSourceComment(position);
    }

    static SourceFreshnessOutcome blockedTransclusion(String target) {
        return new BlockedTransclusionSource(target);
    }

    <T> T resolve(
            Function<String, T> onMatches,
            Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment,
            Function<String, T> onBlockedTransclusion);
}

final class MatchingSource implements SourceFreshnessOutcome {

    private final String sourceHash;

    MatchingSource(String sourceHash) {
        this.sourceHash = Objects.requireNonNull(sourceHash, "sourceHash");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onMatches.apply(sourceHash);
    }
}

final class StaleSource implements SourceFreshnessOutcome {

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onStale.get();
    }
}

final class UnclosedSourceComment implements SourceFreshnessOutcome {

    private final int position;

    UnclosedSourceComment(int position) {
        this.position = position;
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onUnclosedComment.apply(position);
    }
}

final class BlockedTransclusionSource implements SourceFreshnessOutcome {

    private final String target;

    BlockedTransclusionSource(String target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public <T> T resolve(
            Function<String, T> onMatches, Supplier<T> onStale,
            Function<Integer, T> onUnclosedComment, Function<String, T> onBlockedTransclusion) {
        Objects.requireNonNull(onMatches, "onMatches");
        Objects.requireNonNull(onStale, "onStale");
        Objects.requireNonNull(onUnclosedComment, "onUnclosedComment");
        Objects.requireNonNull(onBlockedTransclusion, "onBlockedTransclusion");
        return onBlockedTransclusion.apply(target);
    }
}
```

- [x] 3.5 Wire `PublicNoteIndex`/`LinkResolver` into `PrepareHandler.java`. Read the current file in full
      first (its exact shape may have shifted since this task list was written). Five methods change,
      each by threading one new `PublicNoteIndex knownNotes` parameter through:

  **`prepare(...)`** — build the index once, right after admission succeeds, and nest `LinkResolver.resolve`
  inside the existing `MarkdownNormalizer.normalize(...).resolve(...)` call:
```java
    public BridgeResponse prepare(VaultRelativePath notePath, VaultReader vaultReader) {
        NoteIntake.Result intake = new NoteIntake().admit(notePath, vaultReader);
        if (!intake.accepted()) {
            return BridgeResponse.blocked(COMMAND, intake.diagnostics());
        }
        PublicNoteIndex knownNotes = PublicNoteIndex.from(vaultReader);
        return MarkdownNormalizer.normalize(intake.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        resolvedBody -> prepareNormalizedEssay(notePath, vaultReader, intake, resolvedBody, knownNotes),
                        PrepareHandler::transclusionBlockedFailure),
                position -> unclosedCommentFailure(position));
    }
```

  **`prepareNormalizedEssay(...)`** — gains `PublicNoteIndex knownNotes`, passes it to
  `prepareWithInstallLock`:
```java
    private BridgeResponse prepareNormalizedEssay(
            VaultRelativePath notePath, VaultReader vaultReader, NoteIntake.Result intake,
            String normalizedBody, PublicNoteIndex knownNotes) {
        ApprovedBaselineLookup approved = lookupApprovedBaseline(intake, normalizedBody);
        if (approved.failed()) {
            return approved.failureResponse();
        }
        if (approved.snapshot().isPresent()) {
            return mirrorApprovedCandidate(intake.identity(), approved.snapshot().get());
        }
        return prepareWithInstallLock(notePath, vaultReader, intake, normalizedBody, knownNotes);
    }
```
  (`lookupApprovedBaseline`, `mirrorApprovedCandidate` are unchanged — they never needed the body's links
  resolved, only the resolved body's *content* for the diff/mirror, which they already receive as the
  now-fully-resolved `normalizedBody` parameter — no signature change to either.)

  **`prepareWithInstallLock(...)`** — gains `PublicNoteIndex knownNotes`, passes it to
  `prepareAdmittedEssay`:
```java
    private BridgeResponse prepareWithInstallLock(
            VaultRelativePath notePath, VaultReader vaultReader,
            NoteIntake.Result intake, String normalizedBody, PublicNoteIndex knownNotes) {
        ReentrantLock installLock = INSTALL_LOCKS.computeIfAbsent(intake.identity(), ignored -> new ReentrantLock());
        installLock.lock();
        try {
            return prepareAdmittedEssay(notePath, vaultReader, intake.identity(),
                    intake.sourceHash(), normalizedBody, intake.title(), intake.description(), knownNotes);
        } finally {
            installLock.unlock();
        }
    }
```

  **`prepareAdmittedEssay(...)`** — gains `PublicNoteIndex knownNotes`, passes it to
  `prepareTranslatedEssay`:
```java
    private BridgeResponse prepareAdmittedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, String ruTitle, String ruDescription, PublicNoteIndex knownNotes) {
        TranslationJob job = TranslationJob.forSource(ruBody, ruTitle, ruDescription);
        return translateCandidate(job, ruBody, ruTitle, ruDescription).resolve(
                translation -> prepareTranslatedEssay(
                        notePath, vaultReader, identity, sourceHash,
                        ruBody, ruTitle, ruDescription, job, translation, knownNotes),
                failure -> {
                    recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
                    return translationFailure(failure);
                });
    }
```

  **`prepareTranslatedEssay(...)`** — gains `PublicNoteIndex knownNotes`, passes it to `sourceFreshness`
  and adds the fourth `.resolve(...)` arm:
```java
    private BridgeResponse prepareTranslatedEssay(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity identity, String sourceHash,
            String ruBody, String ruTitle, String ruDescription,
            TranslationJob job,
            EnglishTranslation translation, PublicNoteIndex knownNotes) {
        String enBody = translation.body();
        String enTitle = translation.title();
        String enDescription = translation.description();

        EnglishCandidateValidator.Result validation = validateEnglishCandidate(
                ruBody, enBody, enTitle, enDescription);
        if (!validation.valid()) {
            recordWorkflowStatus(notePath, sourceHash, WorkflowState.TRANSLATION_FAILED);
            return BridgeResponse.translationFailed(COMMAND, blockingDiagnostics(validation.diagnostics()));
        }
        return sourceFreshness(notePath, vaultReader, identity, job, knownNotes).resolve(
                currentSourceHash -> {
                    ReferenceMap referenceMap = buildReferenceMap(
                            identity, ruBody, enBody, ruTitle, enTitle, ruDescription, enDescription);
                    BridgeResponse response = installCandidate(identity, ruBody, enBody, ruTitle, enTitle,
                            ruDescription, enDescription, referenceMap);
                    if (response.ok()) {
                        recordWorkflowStatus(notePath, currentSourceHash, WorkflowState.READY_FOR_REVIEW);
                    }
                    return response;
                },
                () -> {
                    recordStaleWorkflowStatus(notePath, vaultReader);
                    return BridgeResponse.stale(COMMAND,
                            Diagnostic.blocking(
                                    "candidate", "Source note changed while translation was in progress."));
                },
                PrepareHandler::unclosedCommentFailure,
                PrepareHandler::transclusionBlockedFailure);
    }
```

  **`sourceFreshness(...)`** (static) — gains `PublicNoteIndex knownNotes`, nests `LinkResolver.resolve`
  the same way `prepare()` does:
```java
    private static SourceFreshnessOutcome sourceFreshness(
            VaultRelativePath notePath, VaultReader vaultReader,
            PublicationIdentity expectedIdentity, TranslationJob job, PublicNoteIndex knownNotes) {
        NoteIntake.Result current = new NoteIntake().admit(notePath, vaultReader);
        if (!current.accepted() || !expectedIdentity.equals(current.identity())) {
            return SourceFreshnessOutcome.stale();
        }
        return MarkdownNormalizer.normalize(current.body()).resolve(
                normalizedBody -> LinkResolver.resolve(normalizedBody, knownNotes).resolve(
                        resolvedBody -> sourceFingerprintMatches(job, resolvedBody, current.title(), current.description())
                                ? SourceFreshnessOutcome.matches(current.sourceHash())
                                : SourceFreshnessOutcome.stale(),
                        SourceFreshnessOutcome::blockedTransclusion),
                SourceFreshnessOutcome::unclosedComment);
    }
```

  Add one new private static helper next to `unclosedCommentFailure` (near the other small
  `BridgeResponse`-building helpers at the bottom of the class):
```java
    private static BridgeResponse transclusionBlockedFailure(String target) {
        return BridgeResponse.translationFailed(COMMAND,
                Diagnostic.blocking("candidate", "Transclusion target \"" + target + "\" is not a public note."));
    }
```

  No new imports are needed in `PrepareHandler.java` — `PublicNoteIndex`, `LinkResolver`, and
  `LinkResolutionOutcome` are all in the same package.

- [x] 3.6 Run `PrepareHandlerTest` and confirm every test passes, including the three written in section 1.

Run: `cd publication-exporter && mvn -q -o test -Dtest=PrepareHandlerTest 2>&1 | tail -150`

- [x] 3.7 Commit.

```bash
git add publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolutionOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PublicNoteIndex.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/LinkResolver.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/SourceFreshnessOutcome.java \
        publication-exporter/src/main/java/dev/eugene/publicationexporter/prepare/PrepareHandler.java \
        publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/PrepareHandlerTest.java
git commit -m "feat(exporter): resolve public/private links and block private transclusions in prepare"
```

## 4. Narrow unit tests for genuinely combinatorial `LinkResolver`/`PublicNoteIndex` logic

Per this project's outside-in discipline, these are the "genuinely combinatorial" cases not already
covered at acceptance scope by section 1: heading-fragment dropping, alias-vs-target-text label choice,
the ambiguous-filename-stem-collision fallback (design.md D4's fix), the public-target-embed degrade
(task 3.3's rationale note), asset-extension case-insensitivity, and a `LinkResolver`-level protected-region
regression check (independent confirmation, not a duplicate of the existing `PrepareHandlerTest` guard).

**Files:**
- Create: `publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java`

- [x] 4.1 Write and verify all of the following tests in one new file:

```java
package dev.eugene.publicationexporter.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class LinkResolverTest {

    private static final PublicNoteIndex ONE_PUBLIC_NOTE =
            new PublicNoteIndex(Map.of("Заметка о времени", "/essays/notes-on-time/"));

    private static String resolvedBodyOrFail(String body, PublicNoteIndex knownNotes) {
        return LinkResolver.resolve(body, knownNotes).resolve(
                resolved -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));
    }

    @Test
    void headingFragmentIsDroppedFromBothResolutionAndLabel() {
        String body = "See [[Заметка о времени#Some Heading]].";

        assertEquals("See [Заметка о времени](/essays/notes-on-time/).",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void aliasWinsOverTargetTextAsLabelEvenWithAHeadingFragment() {
        String body = "See [[Заметка о времени#Some Heading|a great essay]].";

        assertEquals("See [a great essay](/essays/notes-on-time/).",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void assetExtensionMatchingIsCaseInsensitive() {
        String body = "![[Diagram.PNG]]";

        assertEquals(body, resolvedBodyOrFail(body, new PublicNoteIndex(Map.of())));
    }

    @Test
    void embedOfAPublicNoteDegradesToALinkInsteadOfInliningContent() {
        String body = "![[Заметка о времени]]";

        assertEquals("[Заметка о времени](/essays/notes-on-time/)",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void linkLikeTextInsideInlineCodeIsNeverResolved() {
        String body = "Example: `[[Заметка о времени]]` is wiki-link syntax.";

        assertEquals(body, resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void privateTransclusionReportsTheOffendingTargetText() {
        PublicNoteIndex noKnownNotes = new PublicNoteIndex(Map.of());
        String body = "![[Черновик]]";

        String blockedTarget = LinkResolver.resolve(body, noKnownNotes).resolve(
                resolved -> fail("Expected a blocked transclusion but resolution succeeded: " + resolved),
                target -> target);

        assertEquals("Черновик", blockedTarget);
    }

    @Test
    void fromBuildsARouteForAnAdmittedPublishedEssay() {
        String note = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: notes-on-time
                id: 91aa-notes-on-time
                title: Заметка о времени
                description: A valid description.
                ---
                # Заметка о времени

                Public prose.""";
        VaultRelativePath path = VaultRelativePath.of("blog/Заметка о времени.md");
        PublicNoteIndex index = PublicNoteIndex.from(VaultReader.createNull(Map.of(path, note)));

        assertEquals("/essays/notes-on-time/", index.routeFor("Заметка о времени").orElseThrow());
    }

    @Test
    void filenameStemCollisionAcrossTwoDirectoriesFallsBackToTheSafeLabelForBothNotes() {
        String noteInBlog = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: essay-one
                id: 1111-essay-one
                title: Duplicate Title
                description: A valid description.
                ---
                # Duplicate Title

                First copy.""";
        String noteInArchive = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: essay-two
                id: 2222-essay-two
                title: Duplicate Title
                description: A valid description.
                ---
                # Duplicate Title

                Second copy.""";
        VaultRelativePath pathOne = VaultRelativePath.of("blog/Duplicate Title.md");
        VaultRelativePath pathTwo = VaultRelativePath.of("archive/Duplicate Title.md");
        PublicNoteIndex ambiguousIndex = PublicNoteIndex.from(
                VaultReader.createNull(Map.of(pathOne, noteInBlog, pathTwo, noteInArchive)));

        assertEquals("See Duplicate Title.", resolvedBodyOrFail("See [[Duplicate Title]].", ambiguousIndex));
    }
}
```

  `filenameStemCollisionAcrossTwoDirectoriesFallsBackToTheSafeLabelForBothNotes` is the test that would
  have caught this file's header-noted design.md bug (last-write-wins instead of both-removed) — if this
  test passes with the `PublicNoteIndex.from(...)` implementation from task 3.2, the fix is verified; if it
  fails with a `[essay-one](...)` or `[essay-two](...)` route instead of the plain label, the collision
  tracking in `registerIfAdmitted` was not wired correctly — re-check it against task 3.2 before changing
  anything else.

- [x] 4.2 Run the new test class together with `PrepareHandlerTest` and `MarkdownNormalizerTest`, confirm
      all green.

Run: `cd publication-exporter && mvn -q -o test -Dtest=LinkResolverTest,PrepareHandlerTest,MarkdownNormalizerTest 2>&1 | tail -150`

- [x] 4.3 Commit.

```bash
git add publication-exporter/src/test/java/dev/eugene/publicationexporter/prepare/LinkResolverTest.java
git commit -m "test(exporter): add narrow LinkResolver coverage for ambiguity, heading, and asset edge cases"
```

## 5. Full-suite verification

- [x] 5.1 Run the complete `publication-exporter` test suite and confirm every test passes. Baseline going
      into this slice was 489 tests (one known order-dependent flaky test unrelated to this slice — see
      Haft note `note-20260810-2ea5406b` — expected green when run in isolation or most full-suite runs;
      if it alone fails, re-run once before treating anything as a regression).

```bash
cd publication-exporter && mvn -q -o test 2>&1 | tail -150
grep -h "Tests run" target/surefire-reports/*.txt | awk -F'[ ,]+' '{tests+=$3; fail+=$5; err+=$7; skip+=$9} END {print "Tests run:", tests, "Failures:", fail, "Errors:", err, "Skipped:", skip}'
```

- [x] 5.2 Run the OpenSpec strict validation for this change and confirm it passes.

```bash
cd /Users/eugene/Dev/personal-site && openspec validate "s13-links-transclusion-safety" --strict
```

- [x] 5.3 Refresh the graphify code graph (project convention after any code change).

```bash
graphify update .
```

Do not archive the OpenSpec change or touch Haft artifacts from this task list — those steps are owned by
the orchestrating session, not by an implementer subagent.
