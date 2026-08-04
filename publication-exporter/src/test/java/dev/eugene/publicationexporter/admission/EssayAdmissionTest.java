package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.Frontmatter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EssayAdmissionTest {

    private final EssayAdmission admission = new EssayAdmission();

    @Test
    void validEssayIsAccepted() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertTrue(result.accepted());
        assertEquals(PublicationIdentity.of("blog", "essay", "my-essay"), result.identity());
        assertEquals("8f2c-my-essay", result.sourceId());
    }

    @Test
    void unpublishedNoteIsBlockedOnPublishAlone() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(1, result.diagnostics().size());
        assertEquals("publish", result.diagnostics().get(0).field());
    }

    @Test
    void invalidPublicIdIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: My_Essay
                id: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("publicId", result.diagnostics().get(0).field());
    }

    @Test
    void wrongCollectionBlocksBothCollectionAndContentType() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: bibliography
                publicContentType: essay
                publicId: my-essay
                id: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(2, result.diagnostics().size());
        assertEquals("publicCollection", result.diagnostics().get(0).field());
        assertEquals("publicContentType", result.diagnostics().get(1).field());
    }

    @Test
    void wrongContentTypeAloneBlocksOnlyContentType() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: claim
                publicId: my-essay
                id: 8f2c-my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(1, result.diagnostics().size());
        assertEquals("publicContentType", result.diagnostics().get(0).field());
    }

    @Test
    void missingSourceIdIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("id", result.diagnostics().get(0).field());
    }

    @Test
    void blankSourceIdIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: "   "
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("id", result.diagnostics().get(0).field());
    }

    @Test
    void nullSourceIdIsBlocked() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                publicId: my-essay
                id: null
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals("id", result.diagnostics().get(0).field());
    }

    @Test
    void multipleFailuresAreAllReported() {
        Frontmatter frontmatter = Frontmatter.parse("""
                ---
                publish: true
                publicCollection: blog
                publicContentType: essay
                ---
                """);

        EssayAdmission.Result result = admission.admit(frontmatter);

        assertFalseAccepted(result);
        assertEquals(2, result.diagnostics().size());
    }

    private void assertFalseAccepted(EssayAdmission.Result result) {
        org.junit.jupiter.api.Assertions.assertFalse(result.accepted());
    }
}
