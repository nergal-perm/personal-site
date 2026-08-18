package dev.eugene.publicationexporter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.approved.ApprovedSnapshotWorkspace;
import dev.eugene.publicationexporter.bridge.BridgeResponse;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.buildfromreview.BuildFromReviewHandler;
import dev.eugene.publicationexporter.buildfromreview.ReleaseResult;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.candidate.CandidateWorkspace;
import dev.eugene.publicationexporter.intake.NoteIntake;
import dev.eugene.publicationexporter.legacy.ActivationMarkerTestFixtures;
import dev.eugene.publicationexporter.markreviewed.MarkReviewedHandler;
import dev.eugene.publicationexporter.prepare.PrepareHandler;
import dev.eugene.publicationexporter.release.ReleaseOutputStore;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.site.FilesystemManagedSiteInstaller;
import dev.eugene.publicationexporter.translation.TranslationWorker;
import dev.eugene.publicationexporter.vault.VaultAssetReader;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;
import dev.eugene.publicationexporter.workflow.NullWorkflowStatusEditor;
import dev.eugene.publicationexporter.workflow.WorkflowStatusEditor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuratedPageAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String VALID_ABOUT_PAGE = """
            ---
            publish: true
            publicCollection: editorial
            publicContentType: curated_page
            publicId: about
            editorialPage: about
            id: page-about
            title: Обо мне
            publicSearchable: true
            ---
            ## Кратко

            Кратко.

            ## Eyebrow

            Бровь.

            ## Лид

            Лид.

            ## Принципы

            ### Первый

            Принцип.

            ## Колофон

            Колофон.
            """;

    private static final List<PublicField> RU_FIELDS = List.of(
            PublicField.of("title", "Обо мне"),
            PublicField.of("summary", "Кратко."),
            PublicField.of("eyebrow", "Бровь."),
            PublicField.of("lead", "Лид."),
            PublicField.of("principles[0].title", "Первый"),
            PublicField.of("principles[0].text", "Принцип."),
            PublicField.of("colophon", "Колофон."));

    private static final List<PublicField> EN_FIELDS = List.of(
            PublicField.of("title", "About Me"),
            PublicField.of("summary", "Summary."),
            PublicField.of("eyebrow", "Eyebrow."),
            PublicField.of("lead", "Lead."),
            PublicField.of("principles[0].title", "First"),
            PublicField.of("principles[0].text", "Principle."),
            PublicField.of("colophon", "Colophon."));

    @TempDir
    Path siteRoot;

    @Test
    void aboutPageCompletesAdmissionThroughSiteInstallation() throws Exception {
        InstalledPageFiles installed = completeAboutPagePublication();

        assertCuratedPageJson(installed.ru(), "ru", RU_FIELDS);
        assertCuratedPageJson(installed.en(), "en", EN_FIELDS);
    }

    private InstalledPageFiles completeAboutPagePublication() throws Exception {
        AboutPageFixture fixture = assembleAboutPageFixture();
        prepareAboutPage(fixture);
        CandidateSnapshot approved = approveAboutPage(fixture);
        releaseAboutPage(fixture.identity(), fixture.approvedSnapshotWorkspace());
        installAboutPage(fixture.identity(), approved);
        return readInstalledAboutPage();
    }

    private AboutPageFixture assembleAboutPageFixture() {
        VaultRelativePath path = VaultRelativePath.of("editorial/about.md");
        VaultReader vaultReader = VaultReader.createNull(Map.of(path, VALID_ABOUT_PAGE));
        CandidateWorkspace candidateWorkspace = CandidateWorkspace.createNull();
        ApprovedSnapshotWorkspace approvedSnapshotWorkspace = ApprovedSnapshotWorkspace.createNull();
        NoteIntake noteIntake = new NoteIntake(PublicationKinds.installed());
        TranslationWorker translationWorker = TranslationWorker.createNull(
                "English about page body.", EN_FIELDS);
        WorkflowStatusEditor workflowStatusEditor = new NullWorkflowStatusEditor(Map.of(path, VALID_ABOUT_PAGE));
        PrepareHandler prepareHandler = new PrepareHandler(
                noteIntake,
                translationWorker,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                WorkflowStatusEditor.createNull());
        return new AboutPageFixture(
                path,
                vaultReader,
                VaultAssetReader.createNull(),
                noteIntake,
                candidateWorkspace,
                approvedSnapshotWorkspace,
                workflowStatusEditor,
                prepareHandler,
                PublicationIdentity.of("editorial", "curated_page", "about"));
    }

    private void prepareAboutPage(AboutPageFixture fixture) {
        BridgeResponse response = fixture.prepareHandler().prepare(
                fixture.path(), fixture.vaultReader(), fixture.vaultAssetReader());

        assertTrue(response.ok(), response.diagnostics().toString());
        assertEquals(fixture.identity(), response.identity());
    }

    private CandidateSnapshot approveAboutPage(AboutPageFixture fixture) {
        MarkReviewedHandler markReviewedHandler = new MarkReviewedHandler(
                fixture.noteIntake(),
                fixture.candidateWorkspace(),
                fixture.approvedSnapshotWorkspace(),
                fixture.workflowStatusEditor());
        BridgeResponse response = markReviewedHandler.markReviewed(
                fixture.path(), fixture.vaultReader());

        assertTrue(response.ok(), response.diagnostics().toString());
        return fixture.approvedSnapshotWorkspace().read(fixture.identity()).orElseThrow();
    }

    private void releaseAboutPage(
            PublicationIdentity identity, ApprovedSnapshotWorkspace approvedSnapshotWorkspace) {
        ReleaseResult result = new BuildFromReviewHandler(
                approvedSnapshotWorkspace,
                ReleaseOutputStore.createNull(), ActivationMarkerTestFixtures.activatedMarkerStore()).buildFromReview(identity);

        assertTrue(result.ok(), result.message());
    }

    private void installAboutPage(PublicationIdentity identity, CandidateSnapshot approved) throws Exception {
        new FilesystemManagedSiteInstaller(siteRoot).install(identity, approved);
    }

    private InstalledPageFiles readInstalledAboutPage() throws Exception {
        return new InstalledPageFiles(
                Files.readString(siteRoot.resolve("src/data/pages/ru/about.json")),
                Files.readString(siteRoot.resolve("src/data/pages/en/about.json")));
    }

    private static void assertCuratedPageJson(
            String installedJson, String locale, List<PublicField> expectedFields) throws Exception {
        JsonNode page = MAPPER.readTree(installedJson);

        assertTrue(page.isObject());
        assertEquals("about", page.get("id").asText());
        assertEquals("about", page.get("type").asText());
        assertEquals("curated_page", page.get("contentType").asText());
        assertEquals(locale, page.get("language").asText());
        assertEquals(expectedValue(expectedFields, "title"), page.get("title").asText());
        assertEquals(expectedValue(expectedFields, "summary"), page.get("summary").asText());
        assertEquals(expectedValue(expectedFields, "eyebrow"), page.get("eyebrow").asText());
        assertEquals(expectedValue(expectedFields, "lead"), page.get("lead").asText());
        assertEquals(expectedValue(expectedFields, "colophon"), page.get("colophon").asText());
        assertEquals(1, page.get("principles").size());
        assertEquals(expectedValue(expectedFields, "principles[0].title"),
                page.get("principles").get(0).get(0).asText());
        assertEquals(expectedValue(expectedFields, "principles[0].text"),
                page.get("principles").get(0).get(1).asText());
        assertTrue(page.get("searchable").asBoolean());
    }

    private static String expectedValue(List<PublicField> fields, String name) {
        return PublicField.value(fields, name).orElseThrow();
    }

    private record InstalledPageFiles(String ru, String en) {
    }

    private record AboutPageFixture(
            VaultRelativePath path,
            VaultReader vaultReader,
            VaultAssetReader vaultAssetReader,
            NoteIntake noteIntake,
            CandidateWorkspace candidateWorkspace,
            ApprovedSnapshotWorkspace approvedSnapshotWorkspace,
            WorkflowStatusEditor workflowStatusEditor,
            PrepareHandler prepareHandler,
            PublicationIdentity identity) {
    }
}
