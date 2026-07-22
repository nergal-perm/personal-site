package dev.eugene.astroexport.cli;

import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(
    name = "astro-export",
    mixinStandardHelpOptions = true,
    description = "Export explicitly published Obsidian notes into Astro source trees.")
public final class AstroExportCommand implements Callable<Integer> {
  @Override
  public Integer call() {
    return 0;
  }
}
