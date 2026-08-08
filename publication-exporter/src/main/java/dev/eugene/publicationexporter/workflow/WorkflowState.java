package dev.eugene.publicationexporter.workflow;

public final class WorkflowState {

    public static final String NOT_PREPARED = "not_prepared";
    public static final String METADATA_BLOCKED = "metadata_blocked";
    public static final String READY_FOR_REVIEW = "ready_for_review";
    public static final String READY_TO_PUBLISH = "ready_to_publish";
    public static final String TRANSLATION_FAILED = "translation_failed";
    public static final String STALE = "stale";
    public static final String TRANSLATING = "translating";

    private WorkflowState() {
    }
}
