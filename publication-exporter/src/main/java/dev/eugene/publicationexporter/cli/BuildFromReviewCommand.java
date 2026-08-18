package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.legacy.ActivationMarkerStore;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "build-from-review")
public final class BuildFromReviewCommand implements Callable<Integer> {

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--output", required = true)
    Path outputRoot;

    @Option(names = "--collection", required = true)
    String collection;

    @Option(names = "--content-type", required = true)
    String contentType;

    @Option(names = "--id", required = true)
    String publicId;

    @Override
    public Integer call() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        ActivationMarkerStore activationMarkerStore = ActivationMarkerStore.create(reviewDirectory);
        ReleaseOutputStore releaseOutputStore = ReleaseOutputStore.create(outputRoot);
        PublicationIdentity identity = PublicationIdentity.of(collection, contentType, publicId);
        ReleaseResult result = new BuildFromReviewHandler(approvedSnapshotWorkspace, releaseOutputStore, activationMarkerStore)
                .buildFromReview(identity);

        System.out.println(new ObjectMapper().writeValueAsString(result));
        return result.ok() ? 0 : 1;
    }
}
