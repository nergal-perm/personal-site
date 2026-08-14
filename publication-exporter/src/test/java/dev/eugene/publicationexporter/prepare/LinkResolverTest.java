package dev.eugene.publicationexporter.prepare;

import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.intake.NoteIntake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class LinkResolverTest {

    private static final PublicNoteIndex ONE_PUBLIC_NOTE =
            new PublicNoteIndex(Map.of("Заметка о времени", "/essays/notes-on-time/"));

    private static String resolvedBodyOrFail(String body, PublicNoteIndex knownNotes) {
        return LinkResolver.resolve(body, knownNotes).resolve(
                (resolved, privateTargetStems) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));
    }

    @Test
    void headingFragmentIsDroppedFromBothResolutionAndLabel() {
        String body = "See [[Заметка о времени#Some Heading]].";

        assertEquals("See [Заметка о времени](/essays/notes-on-time/).",
                resolvedBodyOrFail(body, ONE_PUBLIC_NOTE));
    }

    @Test
    void linkToBlogNoteTargetResolvesToNotesRoute() {
        PublicNoteIndex index = new PublicNoteIndex(Map.of("My Note", "/notes/my-note/"));

        LinkResolutionOutcome outcome = LinkResolver.resolve("See [[My Note]].", index);

        assertEquals("See [My Note](/notes/my-note/).", outcome.resolve(
                (resolved, privateTargetStems) -> resolved,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked.")));
    }

    @Test
    void resolveCollectsTheStemOfEveryUnresolvedPlainLinkAsAPrivateTargetStem() {
        String body = "See [[Черновик]] and [[Заметка о времени]].";

        Set<String> privateTargetStems = LinkResolver.resolve(body, ONE_PUBLIC_NOTE).resolve(
                (resolved, stems) -> stems,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals(Set.of("Черновик"), privateTargetStems);
    }

    @Test
    void resolveCollectsTheStemOfAPathQualifiedUnresolvedPlainLinkAsAPrivateTargetStem() {
        String body = "See [[private-area/Secret Draft]].";

        Set<String> privateTargetStems = LinkResolver.resolve(body, ONE_PUBLIC_NOTE).resolve(
                (resolved, stems) -> stems,
                target -> fail("Expected a resolved result but transclusion of \"" + target + "\" was blocked."));

        assertEquals(Set.of("Secret Draft"), privateTargetStems);
    }

    @Test
    void unresolvedLinkWithAPathUsesOnlyTheLastSegmentAsItsSafeLabel() {
        assertEquals("See Secret Draft.",
                resolvedBodyOrFail("See [[private-area/Secret Draft]].", new PublicNoteIndex(Map.of())));
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
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void unclosedRepeatedWikilinksResolveWithinTheTimeout() {
        String body = "[[a".repeat(16000);

        resolvedBodyOrFail(body, new PublicNoteIndex(Map.of()));
    }

    @Test
    void privateTransclusionReportsTheOffendingTargetText() {
        PublicNoteIndex noKnownNotes = new PublicNoteIndex(Map.of());
        String body = "![[Черновик]]";

        String blockedTarget = LinkResolver.resolve(body, noKnownNotes).resolve(
                (resolved, privateTargetStems) -> fail("Expected a blocked transclusion but resolution succeeded: " + resolved),
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

        assertEquals("See [about](/about/).", resolvedBodyOrFail("See [[about]].", index));
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
