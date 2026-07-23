package dev.eugene.astroexport.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CodexRunnerTest {
  @TempDir
  Path temp;

  @Test
  void runsBoundedArgumentArrayInResolvedWorkDirectory() throws Exception {
    Path job = temp.resolve("job");
    Files.createDirectory(job);

    CodexRunner.Run result = new CodexRunner().run(
        job,
        List.of("/bin/sh", "-c", "printf '%s' \"$PWD\"; printf err >&2; exit 7"),
        Duration.ofSeconds(5));

    assertEquals(7, result.exitCode());
    assertEquals(job.toRealPath().toString(), result.stdout());
    assertEquals("err", result.stderr());
    assertFalse(result.timedOut());
  }

  @Test
  void mapsTimeoutWithoutRetry() throws Exception {
    Path job = temp.resolve("job");
    Files.createDirectory(job);
    long started = System.nanoTime();

    CodexRunner.Run result = new CodexRunner().run(
        job,
        List.of("/bin/sh", "-c", "printf partial; sleep 5"),
        Duration.ofMillis(100));

    assertEquals(-1, result.exitCode());
    assertTrue(result.stdout().contains("partial"));
    assertTrue(result.timedOut());
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
  }

  @Test
  void rejectsNonDirectoryWorkdir() {
    IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new CodexRunner().run(
            temp.resolve("missing"),
            List.of("/bin/true"),
            Duration.ofSeconds(1)));

    assertTrue(error.getMessage().contains("directory"));
  }
}
