package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.admission.EssayAdmission;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.Frontmatter;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class NoteIntake {

    public Result admit(VaultRelativePath notePath, VaultReader vaultReader) {
        if (!notePath.isWithinVault()) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note path escapes the vault root.")));
        }
        if (!notePath.hasMarkdownExtension()) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note path must name a Markdown file.")));
        }
        if (!vaultReader.exists(notePath)) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
        return admitExistingNote(notePath, vaultReader);
    }

    private Result admitExistingNote(VaultRelativePath notePath, VaultReader vaultReader) {
        try {
            Frontmatter frontmatter = Frontmatter.parse(vaultReader.readSource(notePath));
            EssayAdmission.Result admission = new EssayAdmission().admit(frontmatter);
            if (!admission.accepted()) {
                return Result.blocked(admission.diagnostics());
            }
            return Result.accepted(admission, frontmatter);
        } catch (NoSuchElementException | UncheckedIOException failure) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
    }

    public static final class Result {

        private final EssayAdmission.Result admission;
        private final Frontmatter frontmatter;
        private final List<Diagnostic> diagnostics;

        private Result(EssayAdmission.Result admission, Frontmatter frontmatter, List<Diagnostic> diagnostics) {
            this.admission = admission;
            this.frontmatter = frontmatter;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(EssayAdmission.Result admission, Frontmatter frontmatter) {
            return new Result(
                    Objects.requireNonNull(admission, "admission"),
                    Objects.requireNonNull(frontmatter, "frontmatter"),
                    List.of());
        }

        static Result blocked(List<Diagnostic> diagnostics) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("blocked() requires at least one diagnostic");
            }
            return new Result(null, null, diagnostics);
        }

        public boolean accepted() {
            return diagnostics.isEmpty();
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public PublicationIdentity identity() {
            return admission.identity();
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String body() {
            return frontmatter.body();
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
