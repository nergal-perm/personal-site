package dev.eugene.publicationexporter.translation;

import dev.eugene.publicationexporter.reference.PublicField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TranslationJobTest {

    @Test
    void generatesNonBlankIdAndFingerprint() {
        TranslationJob job = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));

        assertNotNull(job.id());
        assertNotEquals("", job.id().strip());
        assertNotNull(job.sourceFingerprint());
    }

    @Test
    void sameSourceProducesSameFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));
        TranslationJob second = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));

        assertEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentSourceProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));
        TranslationJob second = TranslationJob.forSource("changed body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentTitleProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title one"), PublicField.of("description", "description")));
        TranslationJob second = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title two"), PublicField.of("description", "description")));

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void differentDescriptionProducesDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description one")));
        TranslationJob second = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description two")));

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }

    @Test
    void twoJobsForSameSourceHaveDifferentIds() {
        TranslationJob first = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));
        TranslationJob second = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"), PublicField.of("description", "description")));

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void differentFieldKeysWithSameValuesProduceDifferentFingerprint() {
        TranslationJob first = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"),
                PublicField.of("description", "description"),
                PublicField.of("use", "same value")));
        TranslationJob second = TranslationJob.forSource("body", List.of(
                PublicField.of("title", "title"),
                PublicField.of("description", "description"),
                PublicField.of("boundary", "same value")));

        assertNotEquals(first.sourceFingerprint(), second.sourceFingerprint());
    }
}
