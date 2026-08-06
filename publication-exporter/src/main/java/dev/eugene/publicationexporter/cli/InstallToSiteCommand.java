package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.installtosite.InstallToSiteHandler;
import dev.eugene.publicationexporter.installtosite.InstallToSiteResult;
import dev.eugene.publicationexporter.site.ManagedSiteInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "install-to-site")
public final class InstallToSiteCommand implements Callable<Integer> {

    @Option(names = "--review", required = true)
    Path reviewDirectory;

    @Option(names = "--site", required = true)
    Path siteRoot;

    @Option(names = "--collection", required = true)
    String collection;

    @Option(names = "--content-type", required = true)
    String contentType;

    @Option(names = "--id", required = true)
    String publicId;

    @Override
    public Integer call() throws Exception {
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.create(reviewDirectory);
        ManagedSiteInstaller managedSiteInstaller = ManagedSiteInstaller.create(siteRoot);
        PublicationIdentity identity = PublicationIdentity.of(collection, contentType, publicId);
        InstallToSiteResult result = new InstallToSiteHandler(approvedSnapshotWorkspace, managedSiteInstaller)
                .installToSite(identity);

        System.out.println(new ObjectMapper().writeValueAsString(result));
        return result.ok() ? 0 : 1;
    }
}
