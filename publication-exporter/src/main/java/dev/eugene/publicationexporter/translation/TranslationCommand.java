package dev.eugene.publicationexporter.translation;

import java.nio.file.Path;
import java.util.List;

public interface TranslationCommand {

    List<String> argsFor(Path workdir, String prompt);
}
