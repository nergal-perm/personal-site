package dev.eugene.publicationexporter.bridge;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

public final class BridgeResponse {

    private final int schemaVersion;
    private final String command;
    private final boolean ok;
    private final String status;
    private final List<Diagnostic> diagnostics;
    private final List<Diagnostic> workspaceHealth;

    private BridgeResponse(
            int schemaVersion,
            String command,
            boolean ok,
            String status,
            List<Diagnostic> diagnostics,
            List<Diagnostic> workspaceHealth) {
        this.schemaVersion = schemaVersion;
        this.command = Objects.requireNonNull(command, "command");
        this.ok = ok;
        this.status = Objects.requireNonNull(status, "status");
        this.diagnostics = diagnostics;
        this.workspaceHealth = workspaceHealth;
    }

    public static BridgeResponse blocked(String command, Diagnostic diagnostic) {
        return new BridgeResponse(2, command, false, "metadata_blocked",
                List.of(diagnostic), List.of());
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
                && workspaceHealth.equals(that.workspaceHealth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaVersion, command, ok, status, diagnostics, workspaceHealth);
    }

    @Override
    public String toString() {
        return "BridgeResponse[schemaVersion=" + schemaVersion + ", command=" + command
                + ", ok=" + ok + ", status=" + status + ", diagnostics=" + diagnostics
                + ", workspaceHealth=" + workspaceHealth + "]";
    }
}
