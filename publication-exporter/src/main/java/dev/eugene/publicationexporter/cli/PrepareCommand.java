package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.translation.CodexTranslationCommand;
import dev.eugene.publicationexporter.translation.ProcessTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;

@Command(name = "prepare")
public final class PrepareCommand implements Callable<Integer> {

    private static final Duration TRANSLATION_TIMEOUT = Duration.ofSeconds(900);

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Option(names = "--note", required = true)
    String notePath;

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--json")
    boolean json;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        TranslationWorker translationWorker = new ProcessTranslationWorker(
                new CodexTranslationCommand(), TRANSLATION_TIMEOUT);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        BridgeResponse response = new PrepareHandler(translationWorker, candidateWorkspace)
                .prepare(VaultRelativePath.of(notePath), vaultReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }
}
