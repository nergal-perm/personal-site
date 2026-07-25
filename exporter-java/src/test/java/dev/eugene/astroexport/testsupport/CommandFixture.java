package dev.eugene.astroexport.testsupport;

import dev.eugene.astroexport.cli.AstroExportCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class CommandFixture {
  public record Result(int exitCode, String stdout, String stderr) {
  }

  public Result run(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    var commandLine = AstroExportCommand.commandLine(new AstroExportCommand());
    commandLine.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
    commandLine.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
    int exitCode = commandLine.execute(args);
    return new Result(
        exitCode,
        out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }
}
