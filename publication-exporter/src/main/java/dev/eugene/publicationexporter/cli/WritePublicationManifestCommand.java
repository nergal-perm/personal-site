package dev.eugene.publicationexporter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.manifest.PublicationManifest;
import dev.eugene.publicationexporter.manifest.PublicationManifestHandler;
import dev.eugene.publicationexporter.vault.VaultReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "write-publication-manifest")
public final class WritePublicationManifestCommand implements Callable<Integer> {

    @Option(names = "--vault", required = true)
    Path vaultRoot;

    @Override
    public Integer call() throws Exception {
        VaultReader vaultReader = VaultReader.create(vaultRoot);
        PublicationManifest manifest = new PublicationManifestHandler().manifest(vaultReader);
        System.out.println(new ObjectMapper().writeValueAsString(manifest));
        return manifest.ok() ? 0 : 1;
    }
}
