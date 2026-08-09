package dev.eugene.publicationexporter.translation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTranslationWorkerJobConfinementTest {

    @Test
    void matchingJobAndFingerprintSucceeds(@TempDir Path jobRoot) throws Exception {
        TranslationJob job = TranslationJob.forSource("ru body", "ru title", "ru description");
        TranslationCommand command = (workdir, prompt) -> {
            assertEquals(realPath(jobRoot), workdir.getParent());
            assertEquals(job.id(), workdir.getFileName().toString());
            writeResult(workdir);
            return successfulCommand();
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                command, Duration.ofSeconds(5), jobRoot);

        TranslationOutcome result = worker.translate(
                job, "ru body", "ru title", "ru description");

        assertEquals("translated body", TranslationResults.translated(result).body());
        assertEquals("translated title", TranslationResults.translated(result).title());
        assertEquals("translated description", TranslationResults.translated(result).description());
    }

    @Test
    void resultOneLevelAboveJobDirectoryIsRejected(@TempDir Path jobRoot) throws Exception {
        Path escapeTarget = jobRoot.resolve("candidate.en.md");
        TranslationCommand command = (workdir, prompt) -> {
            writeString(workdir.resolve("../candidate.en.md"), "escaped content");
            return successfulCommand();
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                command, Duration.ofSeconds(5), jobRoot);

        TranslationOutcome result = worker.translate(
                TranslationJob.forSource("ru body", "ru title", "ru description"),
                "ru body", "ru title", "ru description");

        assertTrue(TranslationResults.failed(result).reason().contains("candidate.en.md"));
        assertEquals("escaped content", Files.readString(escapeTarget));
    }

    @Test
    void tamperedFingerprintIsRejected(@TempDir Path jobRoot) {
        TranslationCommand command = (workdir, prompt) -> {
            writeString(workdir.resolve("job.fingerprint"), "wrong fingerprint");
            writeResult(workdir);
            return successfulCommand();
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                command, Duration.ofSeconds(5), jobRoot);

        TranslationOutcome result = worker.translate(
                TranslationJob.forSource("ru body", "ru title", "ru description"),
                "ru body", "ru title", "ru description");

        assertTrue(TranslationResults.failed(result).reason().contains("fingerprint"));
    }

    @Test
    void symlinkSubstitutionOutsideJobDirectoryIsRejected(@TempDir Path jobRoot) throws Exception {
        Path outside = Files.writeString(jobRoot.resolve("outside.md"), "outside content");
        TranslationCommand command = (workdir, prompt) -> {
            createSymbolicLink(workdir.resolve("candidate.en.md"), outside);
            writeString(workdir.resolve("candidate.en.title.txt"), "translated title");
            writeString(workdir.resolve("candidate.en.description.txt"), "translated description");
            return successfulCommand();
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                command, Duration.ofSeconds(5), jobRoot);

        TranslationOutcome result = worker.translate(
                TranslationJob.forSource("ru body", "ru title", "ru description"),
                "ru body", "ru title", "ru description");

        assertTrue(TranslationResults.failed(result).reason().contains("candidate.en.md"));
    }

    @Test
    void hardLinkSubstitutionOutsideJobDirectoryIsRejected(@TempDir Path jobRoot) throws Exception {
        Path outside = Files.writeString(jobRoot.resolve("outside.md"), "outside content");
        TranslationCommand command = (workdir, prompt) -> {
            createLink(workdir.resolve("candidate.en.md"), outside);
            writeString(workdir.resolve("candidate.en.title.txt"), "translated title");
            writeString(workdir.resolve("candidate.en.description.txt"), "translated description");
            return successfulCommand();
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                command, Duration.ofSeconds(5), jobRoot);

        TranslationOutcome result = worker.translate(
                TranslationJob.forSource("ru body", "ru title", "ru description"),
                "ru body", "ru title", "ru description");

        assertTrue(TranslationResults.failed(result).reason().contains("candidate.en.md"));
    }

    @Test
    void substitutedJobDirectoryIsRejectedWithoutDeletingReplacement(@TempDir Path jobRoot) throws Exception {
        TranslationJob job = TranslationJob.forSource("ru body", "ru title", "ru description");
        Path replacementSentinel = jobRoot.resolve(job.id()).resolve("keep.txt");
        TranslationCommand command = (workdir, prompt) -> {
            move(workdir, jobRoot.resolve("moved-original"));
            createDirectory(workdir);
            writeString(replacementSentinel, "belongs to the replacement directory");
            return successfulCommand();
        };
        ProcessTranslationWorker worker = new ProcessTranslationWorker(
                command, Duration.ofSeconds(5), jobRoot);

        TranslationOutcome result = worker.translate(
                job, "ru body", "ru title", "ru description");

        TranslationResults.failed(result);
        assertTrue(Files.exists(replacementSentinel));
    }

    @Test
    void workspacesForDifferentJobIdsDoNotShareResults(@TempDir Path jobRoot) throws Exception {
        TranslationJob jobA = TranslationJob.forSource("job A body", "job A title", "job A description");
        TranslationJob jobB = TranslationJob.forSource("job B body", "job B title", "job B description");
        JobWorkspace workspaceA = JobWorkspace.createAt(jobRoot, jobA);
        JobWorkspace workspaceB = JobWorkspace.createAt(jobRoot, jobB);
        try {
            writeResult(workspaceA.path());

            assertNotEquals(workspaceA.path(), workspaceB.path());
            assertEquals("translated body", workspaceA.readRequiredResult("candidate.en.md"));
            assertThrows(JobWorkspace.MissingFileException.class,
                    () -> workspaceB.readRequiredResult("candidate.en.md"));
        } finally {
            workspaceA.cleanup();
            workspaceB.cleanup();
        }
    }

    private static void writeResult(Path workdir) {
        writeString(workdir.resolve("candidate.en.md"), "translated body");
        writeString(workdir.resolve("candidate.en.title.txt"), "translated title");
        writeString(workdir.resolve("candidate.en.description.txt"), "translated description");
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void writeString(Path path, String content) {
        try {
            Files.writeString(path, content);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void createLink(Path link, Path target) {
        try {
            Files.createLink(link, target);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void move(Path source, Path target) {
        try {
            Files.move(source, target);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectory(directory);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static List<String> successfulCommand() {
        return List.of("sh", "-c", "true");
    }
}
