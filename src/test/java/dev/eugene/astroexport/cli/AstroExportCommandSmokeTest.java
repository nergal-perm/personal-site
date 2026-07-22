package dev.eugene.astroexport.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

final class AstroExportCommandSmokeTest {
  @Test
  void rootCommandExitsZeroBeforeBehaviorIsAdded() {
    int exitCode = new CommandLine(new AstroExportCommand()).execute();
    assertEquals(0, exitCode);
  }
}
