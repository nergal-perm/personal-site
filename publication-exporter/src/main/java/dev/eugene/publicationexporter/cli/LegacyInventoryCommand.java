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

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Integer call() throws Exception {
        rejectMultipleModes();
        LegacyWorkspaceInventoryHandler inventoryHandler = inventoryHandler();
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
        if (draftFile != null && decisionFile != null) {
            throw new CommandLine.ParameterException(
                    new CommandLine(this), "--draft and --validate are mutually exclusive");
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
}
