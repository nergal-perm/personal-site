package dev.eugene.publicationexporter.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "publication-exporter", subcommands = {
        InspectPublicationCommand.class, PrepareCommand.class, MarkReviewedCommand.class,
        BuildFromReviewCommand.class, InstallToSiteCommand.class })
public final class Main implements Runnable {

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        throw new CommandLine.ParameterException(
                new CommandLine(this), "Missing required subcommand");
    }
}
