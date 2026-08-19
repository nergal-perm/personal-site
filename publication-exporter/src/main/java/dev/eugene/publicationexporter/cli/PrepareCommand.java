package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.MigrationCatalogStore;
import dev.eugene.publicationexporter.legacy.MigrationJournalStore;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.translation.ProcessTranslationWorker;
import dev.eugene.publicationexporter.translation.TranslationEngineConfiguration;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(name = "prepare")
public final class PrepareCommand implements Callable<Integer> {

    private static final Duration TRANSLATION_TIMEOUT = Duration.ofSeconds(900);

    private final Function<Path, TranslationWorker> translationWorkerForJobRoot;

    public PrepareCommand() {
        this(Path.of(System.getProperty("user.dir")), System.getenv());
    }

    PrepareCommand(Path exporterRoot, Map<String, String> environment) {
        this(jobRoot -> configuredWorker(jobRoot, exporterRoot, environment));
    }

    PrepareCommand(TranslationWorker translationWorker) {
        Objects.requireNonNull(translationWorker, "translationWorker");
        this.translationWorkerForJobRoot = ignored -> translationWorker;
    }

    private PrepareCommand(Function<Path, TranslationWorker> translationWorkerForJobRoot) {
        this.translationWorkerForJobRoot = Objects.requireNonNull(
                translationWorkerForJobRoot, "translationWorkerForJobRoot");
    }

    private static TranslationWorker configuredWorker(
            Path jobRoot, Path exporterRoot, Map<String, String> environment) {
        try {
            return new ProcessTranslationWorker(
                    TranslationEngineConfiguration.commandFor(exporterRoot, environment),
                    TRANSLATION_TIMEOUT, jobRoot);
        } catch (IllegalArgumentException failure) {
            return TranslationWorker.createNullFailing("translation-engine", failure.getMessage());
        }
    }

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Option(names = "--note", required = true)
    String notePath;

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--jobs", required = true)
    Path jobsDirectory;

    @Option(names = "--json")
    boolean json;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        VaultAssetReader vaultAssetReader = VaultAssetReader.create(vaultRoot);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        ActivationMarkerStore activationMarkerStore = ActivationMarkerStore.create(reviewDirectory);
        WorkflowStatusEditor workflowStatusEditor = WorkflowStatusEditor.create(vaultRoot);
        TranslationWorker translationWorker = translationWorkerForJobRoot.apply(jobsDirectory);
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        PrepareHandler handler = new PrepareHandler(
                noteIntake, translationWorker, candidateWorkspace, approvedSnapshotWorkspace,
                workflowStatusEditor, activationMarkerStore, MigrationJournalStore.create(reviewDirectory),
                MigrationCatalogStore.create(reviewDirectory));
        BridgeResponse response = handler.prepare(
                VaultRelativePath.of(notePath), vaultReader, vaultAssetReader);

        System.out.println(new ObjectMapper().writeValueAsString(response));
        return response.ok() ? 0 : 1;
    }

}
