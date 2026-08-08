package dev.eugene.publicationexporter.workflow;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowStateClassifierTest {

    private final WorkflowStateClassifier classifier = new WorkflowStateClassifier();

    @Test
    void candidatePresentIsAlwaysReadyForReview() {
        assertEquals(WorkflowState.READY_FOR_REVIEW,
                classifier.classify(true, true, Optional.of(WorkflowState.STALE)));
        assertEquals(WorkflowState.READY_FOR_REVIEW,
                classifier.classify(true, false, Optional.empty()));
    }

    @Test
    void approvedOnlyIsReadyToPublish() {
        assertEquals(WorkflowState.READY_TO_PUBLISH,
                classifier.classify(false, true, Optional.empty()));
        assertEquals(WorkflowState.READY_TO_PUBLISH,
                classifier.classify(false, true, Optional.of(WorkflowState.TRANSLATION_FAILED)));
    }

    @Test
    void neitherPresentTrustsPersistedTranslationFailedOrStale() {
        assertEquals(WorkflowState.TRANSLATION_FAILED,
                classifier.classify(false, false, Optional.of(WorkflowState.TRANSLATION_FAILED)));
        assertEquals(WorkflowState.STALE,
                classifier.classify(false, false, Optional.of(WorkflowState.STALE)));
    }

    @Test
    void neitherPresentAndNoUsablePersistedValueDefaultsToNotPrepared() {
        assertEquals(WorkflowState.NOT_PREPARED,
                classifier.classify(false, false, Optional.empty()));
        assertEquals(WorkflowState.NOT_PREPARED,
                classifier.classify(false, false, Optional.of(WorkflowState.READY_FOR_REVIEW)));
        assertEquals(WorkflowState.NOT_PREPARED,
                classifier.classify(false, false, Optional.of("garbage")));
    }
}
