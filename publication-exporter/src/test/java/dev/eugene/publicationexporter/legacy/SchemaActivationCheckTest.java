package dev.eugene.publicationexporter.legacy;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaActivationCheckTest {

    @Test
    void legacyCheckExposesItsBlockingReason() {
        assertEquals("some reason", SchemaActivationCheck.legacy("some reason").blockingReason());
    }

    @Test
    void currentCheckHasNoBlockingReason() {
        assertThrows(NoSuchElementException.class, () -> SchemaActivationCheck.current().blockingReason());
    }
}
