package dev.eugene.publicationexporter.workflow;

import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.util.Objects;

public interface WorkflowStatusEditor {

    Result write(VaultRelativePath notePath, String expectedSourceHash, String newValue);

    static WorkflowStatusEditor createNull() {
        return new NullWorkflowStatusEditor();
    }

    final class Result {

        private final boolean wasWritten;
        private final String blockedReason;

        private Result(boolean wasWritten, String blockedReason) {
            this.wasWritten = wasWritten;
            this.blockedReason = blockedReason;
        }

        public static Result written() {
            return new Result(true, null);
        }

        public static Result blocked(String reason) {
            return new Result(false, Objects.requireNonNull(reason, "reason"));
        }

        public boolean isWritten() {
            return wasWritten;
        }

        public String blockedReason() {
            return blockedReason;
        }
    }
}
