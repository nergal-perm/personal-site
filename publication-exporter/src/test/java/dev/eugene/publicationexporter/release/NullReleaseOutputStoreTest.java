package dev.eugene.publicationexporter.release;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullReleaseOutputStoreTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final ReleaseProvenance PROVENANCE =
            ReleaseProvenance.of(IDENTITY, "ru-hash", "en-hash", "ru-hash", "en-hash");

    @Test
    void installRecordsTheInstalledBodiesAndProvenance() {
        NullReleaseOutputStore store = new NullReleaseOutputStore();

        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        assertTrue(store.installed().containsKey(IDENTITY));
        assertEquals(1, store.installed().size());
        NullReleaseOutputStore.InstalledRelease installed = store.installed().get(IDENTITY);
        assertEquals("RU body", installed.ruBody());
        assertEquals("EN body", installed.enBody());
        assertSame(PROVENANCE, installed.provenance());
    }

    @Test
    void aSecondInstallForTheSameIdentityThrows() {
        NullReleaseOutputStore store = new NullReleaseOutputStore();
        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);

        assertThrows(ReleaseAlreadyExistsException.class,
                () -> store.install(IDENTITY, "RU body 2", "EN body 2", PROVENANCE));
    }

    @Test
    void interfaceFactoryReturnsAFreshEmptyStore() {
        ReleaseOutputStore store = ReleaseOutputStore.createNull();
        assertTrue(((NullReleaseOutputStore) store).installed().isEmpty());

        store.install(IDENTITY, "RU body", "EN body", PROVENANCE);
        // no exception: a fresh nulled store starts empty, mirroring ApprovedSnapshotWorkspace.createNull()
    }
}
