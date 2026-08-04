package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.Frontmatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EssayAdmission {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final String REQUIRED_COLLECTION = "blog";
    private static final String REQUIRED_CONTENT_TYPE = "essay";

    public Result admit(Frontmatter frontmatter) {
        if (!isPublished(frontmatter)) {
            return Result.blocked(List.of(publishDiagnostic()));
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String collection = requireCollection(frontmatter, diagnostics);
        String contentType = requireContentType(frontmatter, collection, diagnostics);
        String sourceId = requireSourceId(frontmatter, diagnostics);

        if (!diagnostics.isEmpty()) {
            return Result.blocked(diagnostics);
        }
        return Result.accepted(PublicationIdentity.of(collection, contentType, publicId), sourceId);
    }

    private boolean isPublished(Frontmatter frontmatter) {
        return frontmatter.flag("publish");
    }

    private Diagnostic publishDiagnostic() {
        return Diagnostic.blocking("publish", "must be true; allowed value: true");
    }

    private String requireValidPublicId(Frontmatter frontmatter, List<Diagnostic> diagnostics) {
        String publicId = frontmatter.string("publicId").filter(this::isSlug).orElse(null);
        if (publicId == null) {
            diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
        }
        return publicId;
    }

    private boolean isSlug(String candidate) {
        return PUBLIC_ID_SLUG.matcher(candidate).matches();
    }

    private String requireCollection(Frontmatter frontmatter, List<Diagnostic> diagnostics) {
        String collection = frontmatter.string("publicCollection").orElse(null);
        if (!REQUIRED_COLLECTION.equals(collection)) {
            diagnostics.add(Diagnostic.blocking("publicCollection",
                    "must be \"" + REQUIRED_COLLECTION + "\""));
        }
        return collection;
    }

    private String requireContentType(Frontmatter frontmatter, String collection, List<Diagnostic> diagnostics) {
        String contentType = frontmatter.string("publicContentType").orElse(null);
        if (!REQUIRED_COLLECTION.equals(collection)) {
            diagnostics.add(Diagnostic.blocking("publicContentType",
                    "requires a valid publicCollection to determine allowed values"));
        } else if (!REQUIRED_CONTENT_TYPE.equals(contentType)) {
            diagnostics.add(Diagnostic.blocking("publicContentType",
                    "must be \"" + REQUIRED_CONTENT_TYPE + "\""));
        }
        return contentType;
    }

    private String requireSourceId(Frontmatter frontmatter, List<Diagnostic> diagnostics) {
        String sourceId = frontmatter.string("id").filter(value -> !value.isBlank()).orElse(null);
        if (sourceId == null) {
            diagnostics.add(Diagnostic.blocking("id", "Note has no source ID."));
        }
        return sourceId;
    }

    public static final class Result {

        private final PublicationIdentity identity;
        private final String sourceId;
        private final List<Diagnostic> diagnostics;

        private Result(PublicationIdentity identity, String sourceId, List<Diagnostic> diagnostics) {
            this.identity = identity;
            this.sourceId = sourceId;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(PublicationIdentity identity, String sourceId) {
            return new Result(
                    Objects.requireNonNull(identity, "identity"),
                    Objects.requireNonNull(sourceId, "sourceId"),
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
            return identity;
        }

        /** Only meaningful when {@link #accepted()} is {@code true}. */
        public String sourceId() {
            return sourceId;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        @Override
        public String toString() {
            return "EssayAdmission.Result[identity=" + identity + ", sourceId=" + sourceId
                    + ", diagnostics=" + diagnostics + "]";
        }
    }
}
