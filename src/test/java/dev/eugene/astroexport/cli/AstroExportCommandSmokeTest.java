package dev.eugene.astroexport.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class AstroExportCommandSmokeTest {
  @Test
  void helpExitsZero() {
    int exitCode = AstroExportCommand.commandLine(new AstroExportCommand()).execute("--help");
    assertEquals(0, exitCode);
  }
}
