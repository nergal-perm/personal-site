package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TranslationJobTest {

    @Test
    void generatesNonBlankIdAndFingerprint() {
        TranslationJob job = TranslationJob.forSource("body", "title", "description");

        assertNotNull(job.id());
        assertNotEquals("", job.id().strip());
        assertNotNull(job.sourceFingerprint());
    }

    @Test
    void sameSourceProducesSameFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description");
        TranslationJob second = TranslationJob.forSource("body", "title", "description");

        assertEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentSourceProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description");
        TranslationJob second = TranslationJob.forSource("changed body", "title", "description");

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentTitleProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", "title one", "description");
        TranslationJob second = TranslationJob.forSource("body", "title two", "description");

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentDescriptionProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description one");
        TranslationJob second = TranslationJob.forSource("body", "title", "description two");

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void twoJobsForSameSourceHaveDifferentIds() {
        TranslationJob first = TranslationJob.forSource("body", "title", "description");
        TranslationJob second = TranslationJob.forSource("body", "title", "description");

        assertNotEquals(first.id(), second.id());
    }
}
