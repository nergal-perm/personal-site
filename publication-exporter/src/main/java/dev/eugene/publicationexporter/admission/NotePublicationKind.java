package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class NotePublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"));

    @Override
    public String collection() {
        return "blog";
    }

    @Override
    public String contentType() {
        return "note";
    }

    @Override
    public String routePrefix() {
        return "notes";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        return AdmittedPublication.accepted(
                this, PublicationIdentity.of(collection(), contentType(), publicId), sourceId, title, description);
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
        return KindContract.of(collection(), contentType(), requiredFields, List.of());
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
            diagnostics.add(Diagnostic.blocking(key, "Note has no " + key + "."));
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
