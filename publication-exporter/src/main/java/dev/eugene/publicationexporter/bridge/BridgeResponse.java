package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class BridgeResponse {

    private final int schemaVersion;
    private final String command;
    private final boolean ok;
    private final String status;
    private final List<Diagnostic> diagnostics;
    private final List<Diagnostic> workspaceHealth;
    private final PublicationIdentity identity;
    private final String candidateState;
    private final String approvedSnapshotState;
    private final String semanticReferenceState;
    private final String releaseState;
    private final ReviewPlan reviewPlan;

    private BridgeResponse(
            int schemaVersion,
            String command,
            boolean ok,
            String status,
            List<Diagnostic> diagnostics,
            List<Diagnostic> workspaceHealth,
            PublicationIdentity identity,
            String candidateState,
            String approvedSnapshotState,
            String semanticReferenceState,
            String releaseState,
            ReviewPlan reviewPlan) {
        this.schemaVersion = schemaVersion;
        this.command = Objects.requireNonNull(command, "command");
        this.ok = ok;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.workspaceHealth = Objects.requireNonNull(workspaceHealth, "workspaceHealth");
        this.identity = identity;
        this.candidateState = candidateState;
        this.approvedSnapshotState = approvedSnapshotState;
        this.semanticReferenceState = semanticReferenceState;
        this.releaseState = releaseState;
        this.reviewPlan = reviewPlan;
    }

    public static BridgeResponse blocked(String command, Diagnostic diagnostic) {
        return blocked(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse blocked(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "metadata_blocked",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
    }

    public static BridgeResponse prepared(String command, PublicationIdentity identity) {
        return new BridgeResponse(2, command, true, "ready_for_review",
                List.of(), List.of(), Objects.requireNonNull(identity, "identity"),
                null, null, null, null, null);
    }

    public static BridgeResponse translationFailed(String command, Diagnostic diagnostic) {
        return translationFailed(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse translationFailed(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "translation_failed",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
    }

    public static BridgeResponse approved(String command, PublicationIdentity identity) {
        return new BridgeResponse(2, command, true, "ready_to_publish",
                List.of(), List.of(), Objects.requireNonNull(identity, "identity"),
                null, null, null, null, null);
    }

    public static BridgeResponse stale(String command, Diagnostic diagnostic) {
        return stale(command, List.of(Objects.requireNonNull(diagnostic, "diagnostic")));
    }

    public static BridgeResponse stale(String command, List<Diagnostic> diagnostics) {
        return new BridgeResponse(2, command, false, "stale",
                List.copyOf(diagnostics), List.of(), null, null, null, null, null, null);
    }

    public static BridgeResponse essayInspected(
            String command,
            String status,
            PublicationIdentity identity,
            String candidateState,
            String approvedSnapshotState,
            String semanticReferenceState,
            String releaseState,
            ReviewPlan reviewPlan) {
        return new BridgeResponse(2, command, true, status, List.of(), List.of(),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(candidateState, "candidateState"),
                Objects.requireNonNull(approvedSnapshotState, "approvedSnapshotState"),
                Objects.requireNonNull(semanticReferenceState, "semanticReferenceState"),
                Objects.requireNonNull(releaseState, "releaseState"),
                reviewPlan);
    }

    @JsonProperty("schemaVersion")
    public int schemaVersion() {
        return schemaVersion;
    }

    @JsonProperty("command")
    public String command() {
        return command;
    }

    @JsonProperty("ok")
    public boolean ok() {
        return ok;
    }

    @JsonProperty("status")
    public String status() {
        return status;
    }

    @JsonProperty("diagnostics")
    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @JsonProperty("workspaceHealth")
    public List<Diagnostic> workspaceHealth() {
        return workspaceHealth;
    }

    @JsonProperty("identity")
    public PublicationIdentity identity() {
        return identity;
    }

    @JsonProperty("candidateState")
    public String candidateState() {
        return candidateState;
    }

    @JsonProperty("approvedSnapshotState")
    public String approvedSnapshotState() {
        return approvedSnapshotState;
    }

    @JsonProperty("semanticReferenceState")
    public String semanticReferenceState() {
        return semanticReferenceState;
    }

    @JsonProperty("releaseState")
    public String releaseState() {
        return releaseState;
    }

    @JsonProperty("reviewPlan")
    public ReviewPlan reviewPlan() {
        return reviewPlan;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BridgeResponse that)) {
            return false;
        }
        return schemaVersion == that.schemaVersion
                && ok == that.ok
                && command.equals(that.command)
                && status.equals(that.status)
                && diagnostics.equals(that.diagnostics)
                && workspaceHealth.equals(that.workspaceHealth)
                && Objects.equals(identity, that.identity)
                && Objects.equals(candidateState, that.candidateState)
                && Objects.equals(approvedSnapshotState, that.approvedSnapshotState)
                && Objects.equals(semanticReferenceState, that.semanticReferenceState)
                && Objects.equals(releaseState, that.releaseState)
                && Objects.equals(reviewPlan, that.reviewPlan);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, command, ok, status, diagnostics, workspaceHealth,
                identity, candidateState, approvedSnapshotState, semanticReferenceState, releaseState, reviewPlan);
    }

    @Override
    public String toString() {
        return "BridgeResponse[schemaVersion=" + schemaVersion + ", command=" + command
                + ", ok=" + ok + ", status=" + status + ", diagnostics=" + diagnostics
                + ", workspaceHealth=" + workspaceHealth + ", identity=" + identity
                + ", candidateState=" + candidateState + ", approvedSnapshotState=" + approvedSnapshotState
                + ", semanticReferenceState=" + semanticReferenceState + ", releaseState=" + releaseState
                + ", reviewPlan=" + reviewPlan + "]";
    }
}
