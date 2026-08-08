package dev.eugene.publicationexporter.workflow;

import java.util.Optional;

public final class WorkflowStateClassifier {

    public String classify(boolean candidatePresent, boolean approvedPresent,
            Optional<String> persistedWorkflowStatus) {
        if (candidatePresent) {
            return WorkflowState.READY_FOR_REVIEW;
        }
        if (approvedPresent) {
            return WorkflowState.READY_TO_PUBLISH;
        }
        return persistedWorkflowStatus
                .filter(this::isDurableFailureState)
                .orElse(WorkflowState.NOT_PREPARED);
    }

    private boolean isDurableFailureState(String status) {
        return WorkflowState.TRANSLATION_FAILED.equals(status) || WorkflowState.STALE.equals(status);
    }
}
