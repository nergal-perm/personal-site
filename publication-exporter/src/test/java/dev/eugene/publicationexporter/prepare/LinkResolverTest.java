package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.intake.NoteIntake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class LinkResolverTest {

    // Baked-route assertions updated: headingFragmentIsDroppedFromBothResolutionAndLabel,
    // linkToBlogNoteTargetResolvesToNotesRoute, aliasWinsOverTargetTextAsLabelEvenWithAHeadingFragment,
    // fromBuildsCollectionlessRouteForAnAdmittedCuratedAboutPage.
    private static final PublicNoteIndex ONE_PUBLIC_NOTE = onePublicNote();

    private static PublicNoteIndex onePublicNote() {
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
                Public prose.""";
        return PublicNoteIndex.from(
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/Заметка о времени.md"), note)),
                new NoteIntake(PublicationKinds.installed()));
    }

    private static PublicNoteIndex knownNotesWithOneAdmittedTarget() {
        String target = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: target
                id: vault-source-id-target
                title: Target
                description: A valid description.
                ---
                Target body.""";
        return PublicNoteIndex.from(
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/Target.md"), target)),
                new NoteIntake(PublicationKinds.installed()));
    }

    private static String resolvedBodyOrFail(String body, PublicNoteIndex knownNotes) {
        return LinkResolver.resolve(body, knownNotes).resolve(
                (resolved, ignoredOccurrences) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));
    }

    @Test
    void headingFragmentIsDroppedFromBothResolutionAndLabel() {
        String body = "See [[Заметка о времени#Some Heading]].";

        assertEquals("See [Заметка о времени](ref:91aa-notes-on-time).",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void linkToBlogNoteTargetResolvesToDurableReferenceMarker() {
        String note = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: note
                publicId: my-note
                id: source-id-my-note
                title: My Note
                description: A valid description.
                ---
                Public note prose.""";
        PublicNoteIndex index = PublicNoteIndex.from(
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/My Note.md"), note)),
                new NoteIntake(PublicationKinds.installed()));

        LinkResolutionOutcome outcome = LinkResolver.resolve("See [[My Note]].", index);

        assertEquals("See [My Note](ref:source-id-my-note).", outcome.resolve(
                (resolved, ignoredOccurrences) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
    }

    @Test
    void admittedNonEmbedTargetGetsADurableReferenceMarkerNotABakedRoute() {
        LinkResolutionOutcome outcome = LinkResolver.resolve(
                "See [[Target]].", knownNotesWithOneAdmittedTarget());

        assertEquals("See [Target](ref:vault-source-id-target).", outcome.resolve(
                (resolved, ignoredOccurrences) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
    }

    @Test
    void markerEncodesSourceIdsThatWouldOtherwiseTerminateMarkdownDestinations() {
        String target = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: target
                id: target)evil
                title: Target
                description: A valid description.
                ---
                Target body.""";
        PublicNoteIndex index = PublicNoteIndex.from(
                VaultReader.createNull(Map.of(VaultRelativePath.of("blog/Target.md"), target)),
                new NoteIntake(PublicationKinds.installed()));

        assertEquals("See [Target](ref:target%29evil).",
                resolvedBodyOrFail("See [[Target]].", index));
    }

    @Test
    void aliasedAdmittedTargetKeepsItsAliasAsTheLabel() {
        LinkResolutionOutcome outcome = LinkResolver.resolve(
                "See [[Target|My Alias]].", knownNotesWithOneAdmittedTarget());

        assertEquals("See [My Alias](ref:vault-source-id-target).", outcome.resolve(
                (resolved, ignoredOccurrences) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
    }

    @Test
    void repeatedLinksToTheSameAdmittedTargetKeepMarkersAndOccurrenceOrder() {
        String body = "See [[Target]] and then [[Target|Again]].";

        LinkResolutionOutcome outcome = LinkResolver.resolve(body, knownNotesWithOneAdmittedTarget());
        List<LinkOccurrence> occurrences = outcome.resolve(
                (resolved, seen) -> seen,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));
        String resolvedBody = outcome.resolve(
                (resolved, ignoredOccurrences) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals("See [Target](ref:vault-source-id-target) and then [Again](ref:vault-source-id-target).",
                resolvedBody);
        assertEquals(2, occurrences.size());
        assertEquals(List.of("Target", "Target"), occurrences.stream().map(LinkOccurrence::targetStem).toList());
        assertEquals(List.of("Target", "Again"), occurrences.stream().map(LinkOccurrence::label).toList());
        assertEquals(List.of(Optional.of("/essays/target/"), Optional.of("/essays/target/")),
                occurrences.stream().map(LinkOccurrence::route).toList());
        assertTrue(occurrences.get(0).spanStart() < occurrences.get(1).spanStart());
    }

    @Test
    void resolvedOutcomeReportsOccurrencesInSourceOrderForPublicAndPrivateTargets() {
        PublicNoteIndex knownNotes = PublicNoteIndex.from(
                VaultReader.createNull(Map.of(
                        VaultRelativePath.of("blog/public-essay.md"),
                        "---\npublish: true\npublicCollection: blog\npublicContentType: essay\npublicId: pub-1\nid: pub-1\ntitle: Public\ndescription: d\n---\nBody.")),
                new NoteIntake(PublicationKinds.installed()));
        String body = "See [[private-note]] and also [[public-essay]].";

        List<LinkOccurrence> occurrences = LinkResolver.resolve(body, knownNotes).resolve(
                (resolvedBody, seen) -> seen,
                target -> fail("expected resolved links, got blocked transclusion: " + target));

        assertEquals(2, occurrences.size());
        assertEquals("private-note", occurrences.get(0).targetStem());
        assertTrue(occurrences.get(0).route().isEmpty());
        assertEquals("public-essay", occurrences.get(1).targetStem());
        assertEquals(Optional.of("/essays/pub-1/"), occurrences.get(1).route());
    }

    @Test
    void resolveCollectsTheStemOfEveryUnresolvedPlainLinkAsAPrivateTargetStem() {
        String body = "See [[Черновик]] and [[Заметка о времени]].";

        List<LinkOccurrence> occurrences = LinkResolver.resolve(body, ONE_PUBLIC_NOTE).resolve(
                (resolved, seenOccurrences) -> seenOccurrences,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals(Set.of("Черновик"), occurrences.stream()
                .filter(occurrence -> occurrence.route().isEmpty())
                .map(LinkOccurrence::targetStem)
                .collect(Collectors.toSet()));
    }

    @Test
    void resolvePreservesSourceOrderForUnresolvedPrivateTargetStems() {
        String body = "See [[Черновик]] then [[Черновик 2]].";

        List<LinkOccurrence> occurrences = LinkResolver.resolve(body, ONE_PUBLIC_NOTE).resolve(
                (resolved, seenOccurrences) -> seenOccurrences,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals(List.of("Черновик", "Черновик 2"), occurrences.stream()
                .map(LinkOccurrence::targetStem)
                .toList());
    }

    @Test
    void resolveCollectsTheStemOfAPathQualifiedUnresolvedPlainLinkAsAPrivateTargetStem() {
        String body = "See [[private-area/Secret Draft]].";

        List<LinkOccurrence> occurrences = LinkResolver.resolve(body, ONE_PUBLIC_NOTE).resolve(
                (resolved, seenOccurrences) -> seenOccurrences,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals(Set.of("Secret Draft"), occurrences.stream()
                .map(LinkOccurrence::targetStem)
                .collect(Collectors.toSet()));
    }

    @Test
    void unresolvedLinkWithAPathUsesOnlyTheLastSegmentAsItsSafeLabel() {
        assertEquals("See Secret Draft.",
                resolvedBodyOrFail("See [[private-area/Secret Draft]].", PublicNoteIndex.empty()));
    }

    @Test
    void aliasWinsOverTargetTextAsLabelEvenWithAHeadingFragment() {
        String body = "See [[Заметка о времени#Some Heading|a great essay]].";

        assertEquals("See [a great essay](ref:91aa-notes-on-time).",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void assetExtensionMatchingIsCaseInsensitive() {
        String body = "![[Diagram.PNG]]";

        assertEquals(body, resolvedBodyOrFail(body, PublicNoteIndex.empty()));
    }

    @Test
    void embedOfAPublicNoteDegradesToALinkInsteadOfInliningContent() {
        String body = "![[Заметка о времени]]";

        assertEquals("[Заметка о времени](/essays/notes-on-time/)",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void embedOfAPublicNoteRendersAsALinkButDoesNotProduceAnOccurrence() {
        String body = "![[Заметка о времени]]";

        LinkResolutionOutcome outcome = LinkResolver.resolve(body, ONE_PUBLIC_NOTE);

        assertEquals("[Заметка о времени](/essays/notes-on-time/)", outcome.resolve(
                (resolved, ignoredOccurrences) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
        assertEquals(List.of(), outcome.resolve(
                (ignoredResolved, occurrences) -> occurrences,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
    }

    @Test
    void linkLikeTextInsideInlineCodeIsNeverResolved() {
        String body = "Example: `[[Заметка о времени]]` is wiki-link syntax.";

        assertEquals(body, resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void unclosedRepeatedWikilinksResolveWithinTheTimeout() {
        String body = "[[a".repeat(16000);

        resolvedBodyOrFail(body, PublicNoteIndex.empty());
    }

    @Test
    void privateTransclusionReportsTheOffendingTargetText() {
        PublicNoteIndex noKnownNotes = PublicNoteIndex.empty();
        String body = "![[Черновик]]";

        String blockedTarget = LinkResolver.resolve(body, noKnownNotes).resolve(
                (resolved, ignoredOccurrences) -> fail("Expected a blocked transclusion but resolution succeeded: " + resolved),
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
        PublicNoteIndex index = PublicNoteIndex.from(VaultReader.createNull(Map.of(path, note)),
                new NoteIntake(PublicationKinds.installed()));

        assertEquals("/essays/notes-on-time/", index.routeFor("Заметка о времени").orElseThrow());
    }

    @Test
    void fromBuildsCollectionlessRouteForAnAdmittedCuratedAboutPage() {
        String sourceNote = """
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: source-note
                id: source-note
                title: Source note
                description: A valid description.
                ---
                See [[about]].""";
        String aboutPage = """
                ---
                publish: true
                publicCollection: editorial
                publicContentType: curated_page
                publicId: about
                editorialPage: about
                id: source-about
                title: About
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

                Колофон.""";
        PublicNoteIndex index = PublicNoteIndex.from(
                VaultReader.createNull(Map.of(
                        VaultRelativePath.of("blog/Source note.md"), sourceNote,
                        VaultRelativePath.of("editorial/about.md"), aboutPage)),
                new NoteIntake(PublicationKinds.installed()));

        assertEquals("See [about](ref:source-about).", resolvedBodyOrFail("See [[about]].", index));
        assertEquals("/about/", index.routeFor("about").orElseThrow());
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
                VaultReader.createNull(Map.of(pathOne, noteInBlog, pathTwo, noteInArchive)),
                new NoteIntake(PublicationKinds.installed()));

        assertEquals("See Duplicate Title.", resolvedBodyOrFail("See [[Duplicate Title]].", ambiguousIndex));
    }
}
