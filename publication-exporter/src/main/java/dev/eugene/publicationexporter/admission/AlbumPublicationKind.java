package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.site.YamlScalar;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class AlbumPublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<String> OPTIONAL_INVARIANT_FIELDS =
            List.of("releaseDate", "streamingUrl", "bandcampEmbedUrl");

    @Override
    public String collection() {
        return "music";
    }

    @Override
    public String contentType() {
        return "album";
    }

    @Override
    public String routePrefix() {
        return "music";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);
        String artist = requireNonBlank(frontmatter, "artist", diagnostics);
        String work = requireNonBlank(frontmatter, "work", diagnostics);
        String context = requireNonBlank(frontmatter, "context", diagnostics);
        String association = requireNonBlank(frontmatter, "association", diagnostics);
        requireValidOptionalScalar(frontmatter, "format", diagnostics);
        requireValidOptionalScalar(frontmatter, "care", diagnostics);
        requireValidOptionalScalar(frontmatter, "releaseDate", diagnostics);
        requireValidOptionalScalar(frontmatter, "streamingUrl", diagnostics);
        requireValidOptionalScalar(frontmatter, "bandcampEmbedUrl", diagnostics);
        requireValidScalarList(frontmatter, "listenFor", diagnostics);
        requireValidScalarList(frontmatter, "genreTags", diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        return AdmittedPublication.accepted(
                this,
                PublicationIdentity.of(collection(), contentType(), publicId),
                sourceId,
                translatedFields(frontmatter, title, description, context, association),
                structuredDataFrom(frontmatter, artist, work));
    }

    @Override
    public KindContract contract() {
        return KindContract.of(
                collection(),
                contentType(),
                List.of(
                        FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"),
                        FieldContract.allowedValue("publicCollection", FieldContract.Type.STRING, collection()),
                        FieldContract.allowedValue("publicContentType", FieldContract.Type.STRING, contentType()),
                        FieldContract.matchingPattern("publicId", PUBLIC_ID_SLUG.pattern()),
                        FieldContract.nonBlank("id"),
                        FieldContract.nonBlank("title"),
                        FieldContract.nonBlank("description"),
                        FieldContract.nonBlank("artist"),
                        FieldContract.nonBlank("work"),
                        FieldContract.nonBlank("context"),
                        FieldContract.nonBlank("association")),
                List.of(
                        FieldContract.nonBlank("format"),
                        FieldContract.nonBlank("care"),
                        FieldContract.nonBlank("releaseDate"),
                        FieldContract.nonBlank("streamingUrl"),
                        FieldContract.nonBlank("bandcampEmbedUrl"),
                        FieldContract.nonBlankStringList("listenFor"),
                        FieldContract.nonBlankStringList("genreTags")),
                List.of(),
                List.of());
    }

    private List<PublicField> translatedFields(
            MarkdownNote frontmatter, String title, String description, String context, String association) {
        List<PublicField> fields = new ArrayList<>();
        fields.add(PublicField.of("title", title));
        fields.add(PublicField.of("description", description));
        fields.add(PublicField.of("context", context));
        fields.add(PublicField.of("association", association));
        appendOptionalTranslatedScalar(fields, "format", frontmatter);
        appendOptionalTranslatedScalar(fields, "care", frontmatter);
        appendListenFor(fields, frontmatter);
        return List.copyOf(fields);
    }

    private void appendOptionalTranslatedScalar(List<PublicField> fields, String key, MarkdownNote frontmatter) {
        frontmatter.string(key)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> fields.add(PublicField.of(key, value)));
    }

    private void appendListenFor(List<PublicField> fields, MarkdownNote frontmatter) {
        List<String> listenFor = frontmatter.listOfScalars("listenFor");
        for (int index = 0; index < listenFor.size(); index++) {
            fields.add(PublicField.of("listenFor[" + index + "]", listenFor.get(index)));
        }
    }

    private String structuredDataFrom(MarkdownNote frontmatter, String artist, String work) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("artist: ").append(YamlScalar.doubleQuoted(artist)).append('\n');
        yaml.append("work: ").append(YamlScalar.doubleQuoted(work)).append('\n');
        for (String field : OPTIONAL_INVARIANT_FIELDS) {
            appendOptionalInvariantScalar(yaml, field, frontmatter);
        }
        appendGenreTags(yaml, frontmatter.listOfScalars("genreTags"));
        yaml.append("reviewType: \"album\"\n");
        return yaml.toString();
    }

    private void appendOptionalInvariantScalar(StringBuilder yaml, String key, MarkdownNote frontmatter) {
        frontmatter.string(key)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> yaml.append(key).append(": ")
                        .append(YamlScalar.doubleQuoted(value)).append('\n'));
    }

    private void appendGenreTags(StringBuilder yaml, List<String> genreTags) {
        if (genreTags.isEmpty()) {
            return;
        }
        yaml.append("genreTags:\n");
        for (String tag : genreTags) {
            yaml.append("  - ").append(YamlScalar.doubleQuoted(tag)).append('\n');
        }
    }

    private void requireValidOptionalScalar(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        if (frontmatter.string(key).filter(value -> !value.isBlank()).isPresent()) {
            return;
        }
        if (frontmatter.structuredField(key) == MarkdownNote.StructuredField.ABSENT) {
            return;
        }
        diagnostics.add(Diagnostic.blocking(
                key, "music/album optional " + key + " must be a non-blank string when present."));
    }

    private void requireValidScalarList(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        MarkdownNote.StructuredField shape = frontmatter.structuredField(key);
        if (shape == MarkdownNote.StructuredField.NON_LIST) {
            diagnostics.add(Diagnostic.blocking(key, "music/album " + key + " must be a list."));
            return;
        }
        if (shape == MarkdownNote.StructuredField.POPULATED_LIST
                && frontmatter.listOfScalars(key).isEmpty()) {
            diagnostics.add(Diagnostic.blocking(
                    key, "music/album " + key + " entries must be non-blank strings."));
            return;
        }
        if (frontmatter.listOfScalars(key).stream().anyMatch(value -> value == null || value.isBlank())) {
            diagnostics.add(Diagnostic.blocking(
                    key, "music/album " + key + " entries must be non-blank strings."));
        }
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

    private String requireNonBlank(MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        String value = frontmatter.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
        if (value == null) {
            diagnostics.add(Diagnostic.blocking(key, "music/album has no " + key + "."));
        }
        return value;
    }
}
