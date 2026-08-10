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
