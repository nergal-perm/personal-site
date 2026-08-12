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

public final class BookPublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"));
    private static final List<String> OPTIONAL_BOOK_FIELDS = List.of(
            "publication",
            "publicationDate",
            "start",
            "end",
            "readingStatus",
            "use",
            "boundary");

    @Override
    public String collection() {
        return "bibliography";
    }

    @Override
    public String contentType() {
        return "book";
    }

    @Override
    public String routePrefix() {
        return "library";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);
        requireAuthors(frontmatter, diagnostics);
        requireSelectedQuoteAbsent(frontmatter, diagnostics);
        requireOptionalBookFields(frontmatter, diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        List<PublicField> fields = translatedFields(frontmatter, title, description);
        return AdmittedPublication.accepted(
                this,
                PublicationIdentity.of(collection(), contentType(), publicId),
                sourceId,
                fields,
                structuredDataFrom(frontmatter));
    }

    @Override
    public KindContract contract() {
        List<FieldContract> requiredFields = new ArrayList<>();
        requiredFields.add(FieldContract.allowedValue("publish", FieldContract.Type.BOOLEAN, "true"));
        requiredFields.add(FieldContract.allowedValue("publicCollection", FieldContract.Type.STRING, collection()));
        requiredFields.add(FieldContract.allowedValue("publicContentType", FieldContract.Type.STRING, contentType()));
        for (FieldRule rule : FIELD_RULES) {
            requiredFields.add(toFieldContract(rule));
        }
        requiredFields.add(FieldContract.nonBlankStringList("authors"));
        return KindContract.of(
                collection(),
                contentType(),
                requiredFields,
                optionalFieldContracts(),
                List.of("selectedQuote"),
                List.of());
    }

    private List<FieldContract> optionalFieldContracts() {
        return OPTIONAL_BOOK_FIELDS.stream()
                .map(FieldContract::nonBlank)
                .toList();
    }

    private void requireAuthors(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        List<String> authors = frontmatter.listOfScalars("authors");
        if (authors.isEmpty() || authors.stream().anyMatch(String::isBlank)) {
            diagnostics.add(Diagnostic.blocking(
                    "authors",
                    "bibliography/book authors must be a non-empty list of non-blank strings."));
        }
    }

    private void requireSelectedQuoteAbsent(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        if (frontmatter.structuredField("selectedQuote") != MarkdownNote.StructuredField.ABSENT) {
            diagnostics.add(Diagnostic.blocking(
                    "selectedQuote",
                    "bibliography/book selectedQuote is blocked because mixed translated structured quote metadata is not supported by this slice."));
        }
    }

    private void requireOptionalBookFields(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        for (String field : OPTIONAL_BOOK_FIELDS) {
            requireOptionalNonBlankString(frontmatter, field, diagnostics);
        }
    }

    private void requireOptionalNonBlankString(
            MarkdownNote frontmatter,
            String field,
            List<Diagnostic> diagnostics) {
        if (frontmatter.string(field).filter(value -> !value.isBlank()).isPresent()) {
            return;
        }
        if (frontmatter.structuredField(field) == MarkdownNote.StructuredField.ABSENT) {
            return;
        }
        diagnostics.add(Diagnostic.blocking(
                field,
                "bibliography/book optional " + field + " must be a non-blank string when present."));
    }

    private List<PublicField> translatedFields(MarkdownNote frontmatter, String title, String description) {
        List<PublicField> fields = new ArrayList<>();
        fields.add(PublicField.of("title", title));
        fields.add(PublicField.of("description", description));
        appendOptionalTranslatedField(fields, "use", frontmatter);
        appendOptionalTranslatedField(fields, "boundary", frontmatter);
        return List.copyOf(fields);
    }

    private void appendOptionalTranslatedField(List<PublicField> fields, String key, MarkdownNote frontmatter) {
        frontmatter.string(key)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> fields.add(PublicField.of(key, value)));
    }

    private String structuredDataFrom(MarkdownNote frontmatter) {
        StringBuilder yaml = new StringBuilder();
        appendAuthors(yaml, frontmatter.listOfScalars("authors"));
        appendOptionalInvariantField(yaml, "publication", frontmatter);
        appendOptionalInvariantField(yaml, "publicationDate", frontmatter);
        appendOptionalInvariantField(yaml, "start", frontmatter);
        appendOptionalInvariantField(yaml, "end", frontmatter);
        appendOptionalInvariantField(yaml, "readingStatus", frontmatter);
        return yaml.toString();
    }

    private void appendAuthors(StringBuilder yaml, List<String> authors) {
        yaml.append("authors:\n");
        for (String author : authors) {
            yaml.append("  - ").append(YamlScalar.doubleQuoted(author)).append('\n');
        }
    }

    private void appendOptionalInvariantField(StringBuilder yaml, String key, MarkdownNote frontmatter) {
        frontmatter.string(key)
                .filter(value -> !value.isBlank())
                .ifPresent(value -> yaml.append(key)
                        .append(": ")
                        .append(YamlScalar.doubleQuoted(value))
                        .append('\n'));
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
            diagnostics.add(Diagnostic.blocking(key, "bibliography/book has no " + key + "."));
        }
        return value;
    }

    private static FieldContract toFieldContract(FieldRule rule) {
        return switch (rule.kind()) {
            case MUST_EQUAL ->
                    FieldContract.allowedValue(rule.field(), FieldContract.Type.STRING, rule.literalValue());
            case MUST_MATCH -> FieldContract.matchingPattern(rule.field(), rule.pattern().pattern());
            case NON_BLANK -> FieldContract.nonBlank(rule.field());
        };
    }
}
