package dev.eugene.publicationexporter.site;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class CheckContentGateContractTest {

    private static final PublicationIdentity IDENTITY = PublicationIdentity.of("blog", "essay", "my-essay");
    private static final Path SITE_PROJECT_ROOT = Path.of("").toAbsolutePath()
            .resolveSibling("site"); // publication-exporter/ -> ../site

    @TempDir
    Path siteRoot;

    @Test
    void installedOutputPassesTheRealGateWithCuratedPageFixtures() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());

        ProcessResult result = runGate(siteRoot);

        assertEquals(0, result.exitCode(), () -> "gate rejected valid output: " + result.output());
        assertTrue(result.output().contains("Content validation passed successfully"));
    }

    @Test
    void tamperingWithAnInstalledFileIsRejectedBeforeBuild() throws Exception {
        seedCuratedPageFixtures(siteRoot);
        ManagedSiteInstaller.create(siteRoot).install(IDENTITY, essaySnapshot());
        Path ruFile = siteRoot.resolve("src/content/blog/ru/my-essay.md");
        Files.writeString(ruFile, "\ntampered\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        ProcessResult result = runGate(siteRoot);

        assertEquals(1, result.exitCode());
        assertTrue(result.output().toLowerCase().contains("release-provenance-mismatch"));
    }

    private static CandidateSnapshot essaySnapshot() {
        return CandidateSnapshot.of("# My Essay\n\nBody.", "# My Essay (EN)\n\nBody.",
                "My Essay", "My Essay (EN)", "A valid description.", "A valid description (EN).",
                ReferenceMap.empty(IDENTITY, "ru-source-hash", "en-source-hash"));
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
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("check-content.mjs did not complete within 30s");
        }
        return new ProcessResult(process.exitValue(), output);
    }

    private record ProcessResult(int exitCode, String output) {}
}
