package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.reference.ReferenceMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("slow")
class AstroBuildSmokeIT {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final Path SITE_PROJECT_ROOT = Path.of("").toAbsolutePath().resolveSibling("site");

    @TempDir
    Path siteRoot;

    @Test
    void astroBuildSucceedsAgainstTheInstalledOutput() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        CandidateSnapshot snapshot = CandidateSnapshot.of("# My Essay\n\nBody.", "# My Essay (EN)\n\nBody.",
                "My Essay", "My Essay (EN)", "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-source-hash", "en-source-hash"));
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, snapshot);

        int exitCode = runAstroBuild(siteRoot);

        assertEquals(0, exitCode);
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

    private static int runAstroBuild(Path siteRoot) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("npx", "astro", "build", "--force")
                .directory(SITE_PROJECT_ROOT.toFile())
                .redirectErrorStream(true);
        builder.environment().put("ASTRO_CONTENT_DIR", siteRoot.resolve("src/content").toString());
        builder.environment().put("ASTRO_PAGES_DIR", siteRoot.resolve("src/data/pages").toString());
        builder.environment().put("ASTRO_RELEASE_MANIFEST", siteRoot.resolve(".astro-export/release-provenance.json").toString());
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        CompletableFuture<Void> outputDrainer = CompletableFuture.runAsync(() -> drainOutput(process, output));
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS),
                        () -> "astro build did not complete within 180s and did not terminate after destroy\nOutput (truncated):\n"
                                + truncated(output.toString()));
            }
            if (process.exitValue() != 0) {
                fail("astro build failed with exit code " + process.exitValue() + ":\n" + truncated(output.toString()));
            }
            System.out.println("astro build output:\n" + output);
            return process.exitValue();
        } finally {
            outputDrainer.join();
        }
    }

    private static void drainOutput(Process process, StringBuilder output) {
        try (var reader = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
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
}
