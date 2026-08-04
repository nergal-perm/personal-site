package dev.eugene.publicationexporter.translation;

import java.nio.file.Path;
import java.util.List;

public final class CodexTranslationCommand implements TranslationCommand {

    @Override
    public List<String> argsFor(Path workdir, String prompt) {
        return List.of(
                "codex", "exec", "--ephemeral", "--sandbox", "workspace-write",
                "--skip-git-repo-check", "-C", workdir.toString(),
                "--output-last-message", workdir.resolve("agent-message.txt").toString(),
                prompt);
    }
}
