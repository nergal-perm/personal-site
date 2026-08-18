package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventory;
import dev.eugene.publicationexporter.legacy.LegacyWorkspaceInventoryHandler;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "legacy-inventory")
public final class LegacyInventoryCommand implements Callable<Integer> {

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Override
    public Integer call() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace =
                ApprovedSnapshotWorkspace.create(reviewDirectory);
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.create(reviewDirectory);
        LegacyWorkspaceInventory inventory = new LegacyWorkspaceInventoryHandler(
                approvedSnapshotWorkspace, candidateWorkspace).inspect();

        System.out.println(new ObjectMapper().writeValueAsString(inventory));
        return 0;
    }
}
