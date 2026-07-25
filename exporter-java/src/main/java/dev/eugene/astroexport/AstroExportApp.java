package dev.eugene.astroexport;

import dev.eugene.astroexport.cli.AstroExportCommand;

public final class AstroExportApp {
  private AstroExportApp() {
  }

  public static void main(String[] args) {
    int exitCode = AstroExportCommand.commandLine(new AstroExportCommand()).execute(args);
    System.exit(exitCode);
  }
}
