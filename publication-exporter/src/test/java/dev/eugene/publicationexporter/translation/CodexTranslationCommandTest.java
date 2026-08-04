package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodexTranslationCommandTest {

    @Test
    void argsMatchTheEvidencedCodexInvocation() {
        Path workdir = Path.of("/tmp/job-42");

        List<String> args = new CodexTranslationCommand().argsFor(workdir, "translate this");

        assertEquals(List.of(
                "codex", "exec", "--ephemeral", "--sandbox", "workspace-write",
                "--skip-git-repo-check", "-C", "/tmp/job-42",
                "--output-last-message", "/tmp/job-42/agent-message.txt",
                "translate this"), args);
    }
}
