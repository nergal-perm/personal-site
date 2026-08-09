package dev.eugene.publicationexporter.translation;

import java.nio.file.Path;
import java.util.List;

public final class AntigravityTranslationCommand implements TranslationCommand {

    @Override
    public List<String> argsFor(Path workdir, String prompt) {
        return List.of("agy", "--print", "--mode", "accept-edits", "--prompt", prompt);
    }
}
