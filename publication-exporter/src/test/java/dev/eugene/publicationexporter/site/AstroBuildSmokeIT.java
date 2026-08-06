package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("slow")
class AstroBuildSmokeIT {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final Path SITE_PROJECT_ROOT = Path.of("").toAbsolutePath().resolveSibling("site");
    private static final int OUTPUT_TAIL_CAPACITY_BYTES = 64 * 1024;
    private static final long BUILD_TIMEOUT_SECONDS = 180;
    private static final long TERMINATION_TIMEOUT_SECONDS = 2;
    private static final long OUTPUT_DRAIN_TIMEOUT_SECONDS = 2;

    @TempDir
    Path siteRoot;

    @Test
    void astroBuildSucceedsAgainstTheInstalledOutput() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        CandidateSnapshot snapshot = CandidateSnapshot.of("# My Essay\n\nBody.", "# My Essay (EN)\n\nBody.",
                "My Essay", "My Essay (EN)", "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-source-hash", "en-source-hash"));
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, snapshot);
        Path astroProjectRoot = siteRoot.toRealPath();
        copyCodeOwnedAstroScaffold(astroProjectRoot);

        List<Path> dependencyLinks = linkAstroDependencies(astroProjectRoot);
        ProcessResult result;
        try {
            result = runAstroBuild(astroProjectRoot);
        } finally {
            for (Path dependencyLink : dependencyLinks) {
                Files.deleteIfExists(dependencyLink);
            }
        }

        assertEquals(0, result.exitCode(),
                () -> "astro build should accept the installed output.\nOutput tail:\n" + result.outputTail());
        for (String language : List.of("ru", "en")) {
            String route = "/" + language + "/essays/my-essay/index.html";
            assertTrue(result.outputTail().contains(route),
                    () -> "astro build output should report installed route " + route
                            + ".\nOutput tail:\n" + result.outputTail());
            Path generatedFile = astroProjectRoot.resolve("dist").resolve(route.substring(1));
            assertTrue(Files.isRegularFile(generatedFile),
                    () -> "astro build should write installed route " + route + " under the temporary project root");
            String expectedTitle = "ru".equals(language) ? "My Essay" : "My Essay (EN)";
            String generatedContent = Files.readString(generatedFile);
            assertTrue(generatedContent.contains(expectedTitle),
                    () -> "generated route " + route + " should contain installed title " + expectedTitle);
        }
    }

    /*
     * Astro resolves content.config.ts loader bases relative to the Astro project root; it does
     * not read check-content.mjs's ASTRO_* environment-variable convention. Copying only the
     * code-owned scaffold into this temporary root keeps site/ read-only while making the
     * installer's managed trees the only content Astro can discover.
     */
    private static void copyCodeOwnedAstroScaffold(Path temporaryProjectRoot) throws IOException {
        copyFile("astro.config.mjs", temporaryProjectRoot);
        copyFile("tsconfig.json", temporaryProjectRoot);
        copyTreeExcept("src", temporaryProjectRoot,
                List.of(Path.of("content"), Path.of("data/pages"), Path.of("pages")));
        copyFile("src/pages/ru/essays/[id].astro", temporaryProjectRoot);
        copyFile("src/pages/en/essays/[id].astro", temporaryProjectRoot);
        copyTreeExcept("public", temporaryProjectRoot, List.of(Path.of("assets/vault")));
    }

    private static List<Path> linkAstroDependencies(Path temporaryProjectRoot) throws IOException {
        Path nodeModules = temporaryProjectRoot.resolve("node_modules");
        Files.createDirectories(nodeModules.resolve(".bin"));
        Path astro = nodeModules.resolve("astro");
        Path fonts = nodeModules.resolve("@fontsource");
        Path astroExecutable = nodeModules.resolve(".bin/astro");
        Files.createSymbolicLink(astro, SITE_PROJECT_ROOT.resolve("node_modules/astro"));
        Files.createSymbolicLink(fonts, SITE_PROJECT_ROOT.resolve("node_modules/@fontsource"));
        Files.createSymbolicLink(astroExecutable, SITE_PROJECT_ROOT.resolve("node_modules/.bin/astro"));
        return List.of(astroExecutable, fonts, astro);
    }

    private static void copyFile(String relativePath, Path temporaryProjectRoot) throws IOException {
        Path relative = Path.of(relativePath);
        Path destination = temporaryProjectRoot.resolve(relative);
        Files.createDirectories(destination.getParent());
        Files.copy(SITE_PROJECT_ROOT.resolve(relative), destination);
    }

    private static void copyTreeExcept(
            String relativeRoot, Path temporaryProjectRoot, List<Path> excludedSubtrees) throws IOException {
        Path sourceRoot = SITE_PROJECT_ROOT.resolve(relativeRoot);
        Path destinationRoot = temporaryProjectRoot.resolve(relativeRoot);
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                Path relative = sourceRoot.relativize(directory);
                if (isExcluded(relative, excludedSubtrees)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(destinationRoot.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Path relative = sourceRoot.relativize(file);
                if (!isExcluded(relative, excludedSubtrees) && !file.getFileName().toString().equals(".DS_Store")) {
                    Files.copy(file, destinationRoot.resolve(relative));
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isExcluded(Path relative, List<Path> excludedSubtrees) {
        return excludedSubtrees.stream().anyMatch(relative::startsWith);
    }

    // seedCuratedPageFixtures/curatedPageJson: identical to CheckContentGateContractTest's helpers
    // (Task 14) — extract into a small shared test-support class if duplication becomes awkward,
    // per this codebase's own "revisit after a third occurrence" precedent (design.md D3 / S05).

    private static void seedCuratedPageFixtures(Path siteRoot) throws IOException {
        List<String> pageIds = List.of("about", "concepts", "essays", "home", "library", "music", "notes",
                "search", "claims");
        for (String language : List.of("ru", "en")) {
            Path pagesDir = siteRoot.resolve("src/data/pages").resolve(language);
            Files.createDirectories(pagesDir);
            for (String id : pageIds) {
                Files.writeString(pagesDir.resolve(id + ".json"), curatedPageJson(id, language), StandardCharsets.UTF_8);
            }
        }
        Files.createDirectories(siteRoot.resolve("public/assets/vault"));
    }

    private static String curatedPageJson(String id, String language) {
        String type = Map.of("about", "page", "concepts", "concept", "essays", "essay", "home", "page",
                "library", "book", "music", "album", "notes", "note", "claims", "claim", "search", "search")
                .get(id);
        boolean isSystemSearch = "search".equals(id);
        StringBuilder json = new StringBuilder("{");
        json.append("\"id\":\"").append(id).append("\",");
        json.append("\"type\":\"").append(type).append("\",");
        json.append("\"searchable\":false,\"topics\":[],\"links\":[],");
        json.append("\"title\":\"Fixture ").append(language).append(' ').append(id).append("\",");
        json.append("\"summary\":\"Valid synthetic fixture page.\"");
        if (!isSystemSearch) {
            json.append(",\"language\":\"").append(language).append("\",\"sourceLanguage\":\"ru\",");
            json.append("\"translationStatus\":\"").append("ru".equals(language) ? "source" : "generated").append('"');
            if (!"ru".equals(language)) {
                json.append(",\"translationOf\":\"").append(id).append('"');
            }
        }
        json.append("}");
        return json.toString();
    }

    private static ProcessResult runAstroBuild(Path siteRoot) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                "npx", "astro", "build", "--root", siteRoot.toString(), "--force")
                .directory(siteRoot.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        BoundedOutputTail output = new BoundedOutputTail(OUTPUT_TAIL_CAPACITY_BYTES);
        CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(() -> drainOutput(process, output));

        boolean completedInTime;
        boolean terminatedAfterDestroy = true;
        try {
            completedInTime = process.waitFor(BUILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completedInTime) {
                process.destroyForcibly();
                terminatedAfterDestroy = process.waitFor(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        }

        String drainFailure = awaitOutputDrainer(process, outputDrainer);
        String outputTail = output.snapshot();
        if (!completedInTime) {
            fail("astro build did not complete within " + BUILD_TIMEOUT_SECONDS + "s"
                    + (terminatedAfterDestroy ? "" : " and did not terminate after destroy")
                    + ".\nOutput tail:\n" + outputTail);
        }
        if (drainFailure != null) {
            fail(drainFailure + "\nOutput tail:\n" + outputTail);
        }
        System.out.println("astro build output tail:\n" + outputTail);
        return new ProcessResult(process.exitValue(), outputTail);
    }

    private static void drainOutput(Process process, BoundedOutputTail output) {
        try (var reader = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                output.append(buffer, read);
            }
        } catch (IOException ignored) {
            // Intentionally ignore: process output is diagnostic only for this helper.
        }
    }

    private static String awaitOutputDrainer(Process process, CompletableFuture<Void> outputDrainer)
            throws InterruptedException {
        try {
            outputDrainer.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (TimeoutException timeout) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            return "astro build output drainer did not complete within " + OUTPUT_DRAIN_TIMEOUT_SECONDS + "s";
        } catch (ExecutionException failure) {
            return "astro build output drainer failed: " + failure.getCause();
        } catch (InterruptedException interrupted) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void closeProcessOutput(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Best-effort unblock for a drainer whose pipe is still held by a descendant process.
        }
    }

    private record ProcessResult(int exitCode, String outputTail) {}

    private static final class BoundedOutputTail {
        private final byte[] bytes;
        private int size;

        private BoundedOutputTail(int capacity) {
            this.bytes = new byte[capacity];
        }

        private synchronized void append(byte[] source, int length) {
            if (length >= bytes.length) {
                System.arraycopy(source, length - bytes.length, bytes, 0, bytes.length);
                size = bytes.length;
                return;
            }
            int overflow = Math.max(0, size + length - bytes.length);
            if (overflow > 0) {
                System.arraycopy(bytes, overflow, bytes, 0, size - overflow);
                size -= overflow;
            }
            System.arraycopy(source, 0, bytes, size, length);
            size += length;
        }

        private synchronized String snapshot() {
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        }
    }
}
