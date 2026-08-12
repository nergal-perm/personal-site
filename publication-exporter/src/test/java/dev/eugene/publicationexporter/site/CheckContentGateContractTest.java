package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.LegacyCandidateSnapshotFixture;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CheckContentGateContractTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final Path SITE_PROJECT_ROOT = Path.of("").toAbsolutePath()
            .resolveSibling("site"); // publication-exporter/ -> ../site
    private static final int OUTPUT_TAIL_CAPACITY_BYTES = 64 * 1024;
    private static final long GATE_TIMEOUT_SECONDS = 30;
    private static final long TERMINATION_TIMEOUT_SECONDS = 2;
    private static final long OUTPUT_DRAIN_TIMEOUT_SECONDS = 2;

    @TempDir
    Path siteRoot;

    @Test
    void installedOutputPassesTheRealGateWithCuratedPageFixtures() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());

        ProcessResult result = runGate(siteRoot);

        assertEquals(0, result.exitCode(),
                () -> "check-content.mjs should accept valid output but exited with " + result.exitCode()
                        + ".\nOutput (truncated):\n" + truncated(result.output()));
        assertTrue(result.output().contains("Content validation passed successfully"),
                () -> "gate output should include successful validation marker.\nOutput (truncated):\n"
                        + truncated(result.output()));
    }

    @Test
    void tamperingWithAnInstalledFileIsRejectedBeforeBuild() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Files.writeString(ruFile, "\ntampered\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        ProcessResult result = runGate(siteRoot);

        assertEquals(1, result.exitCode(),
                () -> "check-content.mjs should reject modified output but exited with " + result.exitCode()
                        + ".\nOutput (truncated):\n" + truncated(result.output()));
        assertTrue(result.output().toLowerCase().contains("release-provenance-mismatch"),
                () -> "gate output should include release-provenance-mismatch.\nOutput (truncated):\n"
                        + truncated(result.output()));
    }

    @Test
    void leftoverManagedBackupOutsidePayloadRootsDoesNotFailTheRealGate() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Path backup = siteRoot.resolve(".astro-export/managed-backups/src/content/blog/ru")
                .resolve("my-essay.md.backup-00000000-0000-0000-0000-000000000001");
        Files.createDirectories(backup.getParent());
        Files.copy(ruFile, backup);

        ProcessResult result = runGate(siteRoot);

        assertEquals(0, result.exitCode(),
                () -> "check-content.mjs must ignore transaction backups outside payload roots but exited with "
                        + result.exitCode() + ".\nOutput (truncated):\n" + truncated(result.output()));
    }

    @Test
    void installingTheSameReplacementTwiceProducesIdenticalNormalizedProvenance() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller installer = ManagedSiteInstaller.create(siteRoot);
        installer.install(IDENTITY, essaySnapshot());
        installer.install(IDENTITY, replacementEssaySnapshot());
        String firstReplacementProvenance = Files.readString(
                siteRoot.resolve(".astro-export/release-provenance.json"), StandardCharsets.UTF_8);

        installer.install(IDENTITY, replacementEssaySnapshot());

        String secondReplacementProvenance = Files.readString(
                siteRoot.resolve(".astro-export/release-provenance.json"), StandardCharsets.UTF_8);
        assertEquals(firstReplacementProvenance, secondReplacementProvenance);
    }

    @Test
    void replacedOutputPassesTheRealGate() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller installer = ManagedSiteInstaller.create(siteRoot);
        installer.install(IDENTITY, essaySnapshot());
        installer.install(IDENTITY, replacementEssaySnapshot());

        ProcessResult result = runGate(siteRoot);

        assertEquals(0, result.exitCode(),
                () -> "check-content.mjs should accept the replaced generation but exited with "
                        + result.exitCode() + ".\nOutput (truncated):\n" + truncated(result.output()));
        assertTrue(result.output().contains("Content validation passed successfully"),
                () -> "gate output should include successful validation marker.\nOutput (truncated):\n"
                        + truncated(result.output()));
    }

    @Test
    void tamperingWithAReplacedGenerationIsRejectedByTheRealGate() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller installer = ManagedSiteInstaller.create(siteRoot);
        installer.install(IDENTITY, essaySnapshot());
        installer.install(IDENTITY, replacementEssaySnapshot());
        Path enFile = siteRoot.resolve("src/content/blog/en/my-essay.md");
        Files.writeString(enFile, "\ntampered replacement\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        ProcessResult result = runGate(siteRoot);

        assertEquals(1, result.exitCode(),
                () -> "check-content.mjs should reject a tampered replacement but exited with "
                        + result.exitCode() + ".\nOutput (truncated):\n" + truncated(result.output()));
        assertTrue(result.output().toLowerCase().contains("release-provenance-mismatch"),
                () -> "gate output should include release-provenance-mismatch.\nOutput (truncated):\n"
                        + truncated(result.output()));
    }

    private static CandidateSnapshot essaySnapshot() {
        return LegacyCandidateSnapshotFixture.of("# My Essay\n\nBody.", "# My Essay (EN)\n\nBody.",
                "My Essay", "My Essay (EN)", "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-source-hash", "en-source-hash", "ru-title-hash", "en-title-hash", "ru-description-hash", "en-description-hash"));
    }

    private static CandidateSnapshot replacementEssaySnapshot() {
        return LegacyCandidateSnapshotFixture.of("# Replacement Essay\n\nNew body.",
                "# Replacement Essay (EN)\n\nNew body.",
                "Replacement Essay", "Replacement Essay (EN)",
                "A replacement description.", "A replacement description (EN).",
                ReferenceMap.empty(IDENTITY,
                        "replacement-ru-source-hash", "replacement-en-source-hash",
                        "replacement-ru-title-hash", "replacement-en-title-hash",
                        "replacement-ru-description-hash", "replacement-en-description-hash"));
    }

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

    private static ProcessResult runGate(Path siteRoot) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("node", "scripts/check-content.mjs")
                .directory(SITE_PROJECT_ROOT.toFile())
                .redirectErrorStream(true);
        builder.environment().put("ASTRO_CONTENT_DIR", siteRoot.resolve("src/content").toString());
        builder.environment().put("ASTRO_PAGES_DIR", siteRoot.resolve("src/data/pages").toString());
        builder.environment().put("ASTRO_RELEASE_MANIFEST", siteRoot.resolve(".astro-export/release-provenance.json").toString());
        Process process = builder.start();
        BoundedOutputTail output = new BoundedOutputTail(OUTPUT_TAIL_CAPACITY_BYTES);
        CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(() -> drainOutput(process, output));

        boolean completedInTime;
        boolean terminatedAfterDestroy = true;
        try {
            completedInTime = process.waitFor(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
            fail("check-content.mjs did not complete within " + GATE_TIMEOUT_SECONDS + "s"
                    + (terminatedAfterDestroy ? "" : " and did not terminate after destroy")
                    + ".\nOutput (truncated):\n" + truncated(outputTail));
        }
        if (drainFailure != null) {
            fail(drainFailure + "\nOutput (truncated):\n" + truncated(outputTail));
        }
        return new ProcessResult(process.exitValue(), outputTail);
    }

    private static String awaitOutputDrainer(Process process, CompletableFuture<Void> outputDrainer)
            throws InterruptedException {
        try {
            outputDrainer.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (TimeoutException timeout) {
            closeProcessOutput(process);
            outputDrainer.cancel(true);
            return "check-content.mjs output drainer did not complete within " + OUTPUT_DRAIN_TIMEOUT_SECONDS + "s";
        } catch (ExecutionException failure) {
            return "check-content.mjs output drainer failed: " + failure.getCause();
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

    private static String truncated(String output) {
        int maxLength = 4_096;
        return output.length() <= maxLength
                ? output
                : output.substring(0, maxLength) + "... (truncated)";
    }

    private record ProcessResult(int exitCode, String output) {}

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
