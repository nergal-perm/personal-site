package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.note.MarkdownNote;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class EssayAdmission {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final String REQUIRED_COLLECTION = "blog";
    private static final String REQUIRED_CONTENT_TYPE = "essay";
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustEqual("publicCollection", REQUIRED_COLLECTION),
            FieldRule.mustEqual("publicContentType", REQUIRED_CONTENT_TYPE),
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"));

    public static List<FieldRule> fieldRules() {
        return FIELD_RULES;
    }

    public Result admit(MarkdownNote frontmatter) {
        if (!isPublished(frontmatter)) {
            return Result.blocked(List.of(publishDiagnostic()));
        }

        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String collection = requireCollection(frontmatter, diagnostics);
        String contentType = requireContentType(frontmatter, collection, diagnostics);
        String sourceId = requireSourceId(frontmatter, diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);

        if (!diagnostics.isEmpty()) {
            return Result.blocked(diagnostics);
        }
        return Result.accepted(PublicationIdentity.of(collection, contentType, publicId), sourceId, title, description);
    }

    private boolean isPublished(MarkdownNote frontmatter) {
        return frontmatter.flag("publish");
    }

    private Diagnostic publishDiagnostic() {
        return Diagnostic.blocking("publish", "must be true; allowed value: true");
    }

    private String requireValidPublicId(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        String publicId = frontmatter.string("publicId").filter(this::isSlug).orElse(null);
        if (publicId == null) {
            diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
        }
        return publicId;
    }

    private boolean isSlug(String candidate) {
        return PUBLIC_ID_SLUG.matcher(candidate).matches();
    }

    private String requireCollection(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        String collection = frontmatter.string("publicCollection").orElse(null);
        if (!REQUIRED_COLLECTION.equals(collection)) {
            diagnostics.add(Diagnostic.blocking("publicCollection",
                    "must be \"" + REQUIRED_COLLECTION + "\""));
        }
        return collection;
    }

    private String requireContentType(MarkdownNote frontmatter, String collection, List<Diagnostic> diagnostics) {
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

    private String requireSourceId(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        String sourceId = frontmatter.string("id").filter(value -> !value.isBlank()).orElse(null);
        if (sourceId == null) {
            diagnostics.add(Diagnostic.blocking("id", "Note has no source ID."));
        }
        return sourceId;
    }

    private String requireNonBlank(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        String value = frontmatter.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
        if (value == null) {
            diagnostics.add(Diagnostic.blocking(key, "Essay note has no " + key + "."));
        }
        return value;
    }

    public static final class Result {

        private final PublicationIdentity identity;
        private final String sourceId;
        private final String title;
        private final String description;
        private final List<Diagnostic> diagnostics;

        private Result(PublicationIdentity identity, String sourceId, String title, String description,
                List<Diagnostic> diagnostics) {
            this.identity = identity;
            this.sourceId = sourceId;
            this.title = title;
            this.description = description;
            this.diagnostics = List.copyOf(diagnostics);
        }

        static Result accepted(PublicationIdentity identity, String sourceId, String title, String description) {
            return new Result(
                    Objects.requireNonNull(identity, "identity"),
                    Objects.requireNonNull(sourceId, "sourceId"),
                    Objects.requireNonNull(title, "title"),
                    Objects.requireNonNull(description, "description"),
                    List.of());
        }

        static Result blocked(List<Diagnostic> diagnostics) {
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("blocked() requires at least one diagnostic");
            }
            return new Result(null, null, null, null, diagnostics);
        }

        public boolean accepted() {
            return diagnostics.isEmpty();
        }

        public PublicationIdentity identity() {
            return identity;
        }

        public String sourceId() {
            return sourceId;
        }

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        @Override
        public String toString() {
            return "EssayAdmission.Result[identity=" + identity + ", sourceId=" + sourceId
                    + ", title=" + title + ", description=" + description + ", diagnostics=" + diagnostics + "]";
        }
    }
}
