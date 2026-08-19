package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.legacy.LegacyMigrationDecisionCodec;
import dev.eugene.publicationexporter.legacy.LegacyMigrationDecisionValidator;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventory;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventoryHandler;
import dev.eugene.publicationexporter.legacy.MigrationDecisionSet;
import dev.eugene.publicationexporter.legacy.MigrationApplyHandler;
import dev.eugene.publicationexporter.legacy.MigrationCatalogStore;
import dev.eugene.publicationexporter.legacy.MigrationJournalStore;
import dev.eugene.publicationexporter.legacy.MigrationWorkspace;
import dev.eugene.publicationexporter.legacy.SemanticOperationLock;
import dev.eugene.publicationexporter.legacy.ActivationMarker;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.legacy.MigrationGeneration;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;

@Command(name = "legacy-inventory")
public final class LegacyInventoryCommand implements Callable<Integer> {

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--draft")
    Path draftFile;

    @Option(names = "--validate")
    Path decisionFile;

    @Option(names = "--apply")
    Path applyDecisionFile;

    @Option(names = "--roll-forward")
    boolean rollForward;

    @Option(names = "--roll-back")
    boolean rollBack;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        rejectMultipleModes();
        LegacyWorkspaceInventoryHandler inventoryHandler = inventoryHandler();
        if (applyDecisionFile != null) {
            return applyMigration(inventoryHandler);
        }
        if (rollForward) {
            return rollForwardMigration(inventoryHandler);
        }
        if (rollBack) {
            return rollBackMigration(inventoryHandler);
        }
        if (draftFile != null) {
            writeDraftOutsideReviewRoot(inventoryHandler.inspect());
            return 0;
        }
        if (decisionFile != null) {
            validateHumanDecision(inventoryHandler);
            return 0;
        }
        printInventory(inventoryHandler.inspect());
        return 0;
    }

    private void rejectMultipleModes() {
        int selected = (draftFile == null ? 0 : 1) + (decisionFile == null ? 0 : 1)
                + (applyDecisionFile == null ? 0 : 1) + (rollForward ? 1 : 0) + (rollBack ? 1 : 0);
        if (selected > 1) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this), "--draft, --validate, --apply, --roll-forward, and --roll-back are mutually exclusive");
        }
    }

    private LegacyWorkspaceInventoryHandler inventoryHandler() {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace =
                ApprovedSnapshotWorkspace.create(reviewDirectory);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        return new LegacyWorkspaceInventoryHandler(approvedSnapshotWorkspace, candidateWorkspace);
    }

    private void printInventory(LegacyWorkspaceInventory inventory) throws Exception {
        System.out.println(mapper.writeValueAsString(inventory));
    }

    private void writeDraftOutsideReviewRoot(LegacyWorkspaceInventory inventory) throws Exception {
        Path reviewRoot = resolvedPath(reviewDirectory);
        Path destination = resolvedPath(draftFile);
        if (destination.startsWith(reviewRoot)) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this), "Draft destination must be outside the review root");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this), "Draft destination must not already exist");
        }
        Files.createDirectories(destination.getParent());
        Files.writeString(
                destination, new LegacyMigrationDecisionCodec().draftFor(inventory), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private Path resolvedPath(Path candidate) throws Exception {
        Path normalized = candidate.toAbsolutePath().normalize();
        Path existing = normalized;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        return existing.toRealPath().resolve(existing.relativize(normalized)).normalize();
    }

    private void validateHumanDecision(LegacyWorkspaceInventoryHandler inventory) throws Exception {
        String decisionJson = Files.readString(decisionFile, StandardCharsets.UTF_8);
        MigrationDecisionSet decision = new LegacyMigrationDecisionValidator(
                inventory, new LegacyMigrationDecisionCodec()).validate(decisionJson);
        ObjectNode result = mapper.createObjectNode();
        result.put("status", "validated");
        result.put("schemaVersion", decision.schemaVersion());
        result.put("inventorySha256", decision.inventorySha256());
        System.out.println(mapper.writeValueAsString(result));
    }

    private Integer applyMigration(LegacyWorkspaceInventoryHandler inventory) throws Exception {
        MigrationGeneration generation = migrationHandler(inventory).apply(readDecision(applyDecisionFile));
        printMigrationResult("applied", generation);
        return 0;
    }

    private Integer rollForwardMigration(LegacyWorkspaceInventoryHandler inventory) {
        MigrationGeneration generation = migrationHandler(inventory).rollForward();
        printMigrationResult("rolled-forward", generation);
        return 0;
    }

    private Integer rollBackMigration(LegacyWorkspaceInventoryHandler inventory) {
        MigrationGeneration generation = migrationHandler(inventory).rollBack();
        printMigrationResult("rolled-back", generation);
        return 0;
    }

    private MigrationApplyHandler migrationHandler(LegacyWorkspaceInventoryHandler inventory) {
        return new MigrationApplyHandler(inventory, new LegacyMigrationDecisionCodec(),
                MigrationJournalStore.create(reviewDirectory), MigrationCatalogStore.create(reviewDirectory),
                MigrationWorkspace.create(reviewDirectory), SemanticOperationLock.create(reviewDirectory),
                ActivationMarkerStore.create(reviewDirectory));
    }

    private String readDecision(Path source) throws Exception {
        if (source == null || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new CommandLine.ParameterException(new CommandLine(this),
                    "Migration apply requires a separate regular human decision file");
        }
        return Files.readString(source, StandardCharsets.UTF_8);
    }

    private void printMigrationResult(String status, MigrationGeneration generation) {
        ObjectNode result = mapper.createObjectNode();
        result.put("status", status);
        result.put("inventorySha256", generation.inventorySha256());
        result.put("state", generation.state().name());
        System.out.println(result);
    }
}
