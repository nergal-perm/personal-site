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
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ClaimPublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final List<String> RELATIONSHIP_KEYS =
            List.of("supports", "opposes", "assumes", "refines", "contradicts");
    private static final Set<String> RELATIONSHIP_FIELDS = Set.of("label", "target");
    private static final List<FieldRule> FIELD_RULES = List.of(
            FieldRule.mustMatch("publicId", PUBLIC_ID_SLUG, "a lowercase route slug"),
            FieldRule.nonBlank("id"),
            FieldRule.nonBlank("title"),
            FieldRule.nonBlank("description"),
            FieldRule.nonBlank("statement"));

    @Override
    public String collection() {
        return "blog";
    }

    @Override
    public String contentType() {
        return "claim";
    }

    @Override
    public String routePrefix() {
        return "claims";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);
        String statement = requireNonBlank(frontmatter, "statement", diagnostics);
        requireValidStructuredMetadata(frontmatter, diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        List<PublicField> fields = List.of(
                PublicField.of("title", title),
                PublicField.of("description", description),
                PublicField.of("statement", statement));
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
        return KindContract.of(collection(), contentType(), requiredFields, List.of());
    }

    private String structuredDataFrom(MarkdownNote frontmatter) {
        StringBuilder yaml = new StringBuilder();
        for (String key : RELATIONSHIP_KEYS) {
            appendMapList(yaml, key, frontmatter.listOfMaps(key));
        }
        frontmatter.opaqueListYaml("sources").ifPresent(yaml::append);
        return yaml.toString();
    }

    private void requireValidStructuredMetadata(
            MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        for (String relationshipKey : RELATIONSHIP_KEYS) {
            requireValidRelationshipList(frontmatter, relationshipKey, diagnostics);
        }
        requireValidSources(frontmatter, diagnostics);
    }

    private void requireValidSources(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        if (!requireList(frontmatter, "sources", diagnostics)) {
            return;
        }
        frontmatter.opaqueListYaml("sources")
                .filter(yaml -> !ClaimSourceShape.matches(yaml))
                .ifPresent(ignored -> diagnostics.add(invalidSourcesDiagnostic()));
    }

    private void requireValidRelationshipList(
            MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        if (!requireList(frontmatter, key, diagnostics)) {
            return;
        }
        if (frontmatter.structuredField(key) == MarkdownNote.StructuredField.POPULATED_LIST
                && !frontmatter.hasOnlyScalarMapEntries(key)) {
            diagnostics.add(invalidRelationshipDiagnostic(key));
            return;
        }
        for (Map<String, String> relationship : frontmatter.listOfMaps(key)) {
            if (!validRelationship(relationship)) {
                diagnostics.add(invalidRelationshipDiagnostic(key));
                return;
            }
        }
    }

    private boolean requireList(
            MarkdownNote frontmatter, String key, List<Diagnostic> diagnostics) {
        if (frontmatter.structuredField(key) == MarkdownNote.StructuredField.NON_LIST) {
            diagnostics.add(Diagnostic.blocking(key, "blog/claim " + key + " must be a list."));
            return false;
        }
        return true;
    }

    private static boolean validRelationship(Map<String, String> relationship) {
        return RELATIONSHIP_FIELDS.containsAll(relationship.keySet())
                && relationship.containsKey("label")
                && !relationship.get("label").isBlank();
    }

    private static Diagnostic invalidRelationshipDiagnostic(String key) {
        return Diagnostic.blocking(
                key, "blog/claim " + key + " entries require a non-blank label and optional target.");
    }

    private static Diagnostic invalidSourcesDiagnostic() {
        return Diagnostic.blocking(
                "sources", "blog/claim sources entries must match the site claimSource shape.");
    }

    private static void appendMapList(
            StringBuilder yaml, String key, List<Map<String, String>> entries) {
        if (entries.isEmpty()) {
            return;
        }
        yaml.append(key).append(":\n");
        for (Map<String, String> entry : entries) {
            appendMapEntry(yaml, entry);
        }
    }

    private static void appendMapEntry(StringBuilder yaml, Map<String, String> entry) {
        boolean first = true;
        for (Map.Entry<String, String> field : entry.entrySet()) {
            yaml.append(first ? "  - " : "    ")
                    .append(field.getKey())
                    .append(": ")
                    .append(YamlScalar.doubleQuoted(field.getValue()))
                    .append('\n');
            first = false;
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
            diagnostics.add(Diagnostic.blocking(key, "blog/claim has no " + key + "."));
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
