package dev.eugene.astroexport;

import dev.eugene.astroexport.cli.AstroExportCommand;
import picocli.CommandLine;

public final class AstroExportApp {
  private AstroExportApp() {
  }

  public static void main(String[] args) {
    int exitCode = new CommandLine(new AstroExportCommand()).execute(args);
    System.exit(exitCode);
  }
}
