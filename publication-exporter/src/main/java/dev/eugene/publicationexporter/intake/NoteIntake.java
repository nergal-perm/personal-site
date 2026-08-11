package dev.eugene.publicationexporter.intake;

import dev.eugene.publicationexporter.admission.AdmittedPublication;
import dev.eugene.publicationexporter.admission.PublicationKind;
import dev.eugene.publicationexporter.admission.PublicationKinds;
import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.hash.ContentHash;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.vault.VaultReader;
import dev.eugene.publicationexporter.vault.VaultRelativePath;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

public final class NoteIntake {

    private final PublicationKinds publicationKinds;

    public NoteIntake(PublicationKinds publicationKinds) {
        this.publicationKinds = Objects.requireNonNull(publicationKinds, "publicationKinds");
    }

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
            String source = vaultReader.readSource(notePath);
            MarkdownNote frontmatter = MarkdownNote.parse(source);
            String sourceHash = ContentHash.sha256Hex(source);
            AdmittedPublication admission = admitAgainstKind(frontmatter);
            if (!admission.accepted()) {
                return Result.blocked(admission.diagnostics());
            }
            return Result.accepted(admission, frontmatter, sourceHash);
        } catch (NoSuchElementException | UncheckedIOException failure) {
            return Result.blocked(List.of(
                    Diagnostic.blocking("note", "Note was not found in the vault.")));
        }
    }

    private AdmittedPublication admitAgainstKind(MarkdownNote frontmatter) {
        if (!frontmatter.flag("publish")) {
            return AdmittedPublication.blocked(List.of(
                    Diagnostic.blocking("publish", "must be true; allowed value: true")));
        }
        String collection = frontmatter.string("publicCollection").orElse("");
        String contentType = frontmatter.string("publicContentType").orElse("");
        Optional<PublicationKind> kind = publicationKinds.forIdentity(collection, contentType);
        if (kind.isEmpty()) {
            return AdmittedPublication.blocked(List.of(Diagnostic.blocking(
                    "publicContentType", "publicCollection/publicContentType is not a supported publication kind")));
        }
        return kind.get().admit(frontmatter);
    }

    public static final class Result {

        private final AdmittedPublication admission;
        private final MarkdownNote frontmatter;
        private final String sourceHash;
        private final List<Diagnostic> diagnostics;

        private Result(AdmittedPublication admission, MarkdownNote frontmatter,
                String sourceHash, List<Diagnostic> diagnostics) {
            this.admission = admission;
            this.frontmatter = frontmatter;
            this.sourceHash = sourceHash;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(AdmittedPublication admission, MarkdownNote frontmatter, String sourceHash) {
            return new Result(
                    Objects.requireNonNull(admission, "admission"),
                    Objects.requireNonNull(frontmatter, "frontmatter"),
                    Objects.requireNonNull(sourceHash, "sourceHash"),
                    List.of());
        }

        static Result blocked(List<Diagnostic> diagnostics) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("blocked() requires at least one diagnostic");
            }
            return new Result(null, null, null, diagnostics);
        }

        public boolean accepted() {
            return diagnostics.isEmpty();
        }

        public PublicationKind kind() {
            return admission.kind();
        }

        public PublicationIdentity identity() {
            return admission.identity();
        }

        public String body() {
            return frontmatter.body();
        }

        public String sourceHash() {
            return sourceHash;
        }

        public Optional<String> frontmatterString(String key) {
            return frontmatter.string(Objects.requireNonNull(key, "key"));
        }

        public String title() {
            return admission.title();
        }

        public String description() {
            return admission.description();
        }

        public String structuredData() {
            return admission.structuredData();
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
