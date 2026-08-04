package dev.eugene.publicationexporter.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicationIdentityTest {

    @Test
    void accessorsReturnConstructedValues() {
        PublicationIdentity identity = PublicationIdentity.of("blog", "essay", "my-essay");

        assertEquals("blog", identity.publicCollection());
        assertEquals("essay", identity.publicContentType());
        assertEquals("my-essay", identity.publicId());
    }

    @Test
    void equalIdentitiesBuiltSeparatelyAreEqual() {
        assertEquals(
                PublicationIdentity.of("blog", "essay", "my-essay"),
                PublicationIdentity.of("blog", "essay", "my-essay"));
    }

    @Test
    void publicIdIsRejectedAtConstruction() {
        NullPointerException exception = assertThrows(NullPointerException.class,
                () -> PublicationIdentity.of("blog", "essay", null));
        assertEquals("publicId", exception.getMessage());
    }
}
