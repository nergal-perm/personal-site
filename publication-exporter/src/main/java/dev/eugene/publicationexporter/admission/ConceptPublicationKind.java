package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConceptPublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> RELATION_MEMBERS = Set.of("name", "relation");

    @Override
    public String collection() {
        return "concepts";
    }

    @Override
    public String contentType() {
        return "concept";
    }

    @Override
    public String routePrefix() {
        return "concepts";
    }

    @Override
    public AdmittedPublication admit(MarkdownNote frontmatter) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(frontmatter, diagnostics);
        String sourceId = requireNonBlank(frontmatter, "id", diagnostics);
        String title = requireNonBlank(frontmatter, "title", diagnostics);
        String description = requireNonBlank(frontmatter, "description", diagnostics);
        requireValidRelations(frontmatter, diagnostics);
        requireValidExamples(frontmatter, diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        return AdmittedPublication.accepted(
                this,
                PublicationIdentity.of(collection(), contentType(), publicId),
                sourceId,
                translatedFields(frontmatter, title, description),
                "");
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
                        FieldContract.nonBlank("description")),
                List.of(
                        FieldContract.nonBlank("notThis"),
                        FieldContract.nonBlankStringList("examples"),
                        FieldContract.nonBlankStructuredList("relations", List.of("name", "relation"))),
                List.of(),
                List.of());
    }

    private List<PublicField> translatedFields(MarkdownNote frontmatter, String title, String description) {
        List<PublicField> fields = new ArrayList<>();
        fields.add(PublicField.of("title", title));
        fields.add(PublicField.of("description", description));
        appendNotThis(fields, frontmatter);
        appendRelations(fields, frontmatter);
        appendExamples(fields, frontmatter);
        return List.copyOf(fields);
    }

    private void appendNotThis(List<PublicField> fields, MarkdownNote frontmatter) {
        frontmatter.string("notThis")
                .filter(value -> !value.isBlank())
                .ifPresent(value -> fields.add(PublicField.of("notThis", value)));
    }

    private void appendRelations(List<PublicField> fields, MarkdownNote frontmatter) {
        List<Map<String, String>> relations = frontmatter.listOfMaps("relations");
        for (int index = 0; index < relations.size(); index++) {
            Map<String, String> relation = relations.get(index);
            fields.add(PublicField.of("relations[" + index + "].name", relation.get("name")));
            fields.add(PublicField.of("relations[" + index + "].relation", relation.get("relation")));
        }
    }

    private void appendExamples(List<PublicField> fields, MarkdownNote frontmatter) {
        List<String> examples = frontmatter.listOfScalars("examples");
        for (int index = 0; index < examples.size(); index++) {
            fields.add(PublicField.of("examples[" + index + "]", examples.get(index)));
        }
    }

    private void requireValidRelations(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        if (frontmatter.structuredField("relations") == MarkdownNote.StructuredField.NON_LIST) {
            diagnostics.add(Diagnostic.blocking("relations", "concepts/concept relations must be a list."));
            return;
        }
        for (Map<String, String> relation : frontmatter.listOfMaps("relations")) {
            if (!validRelation(relation)) {
                diagnostics.add(Diagnostic.blocking(
                        "relations",
                        "concepts/concept relations entries require non-blank name and relation, no other fields."));
                return;
            }
        }
    }

    private boolean validRelation(Map<String, String> relation) {
        return RELATION_MEMBERS.containsAll(relation.keySet())
                && relation.keySet().containsAll(RELATION_MEMBERS)
                && nonBlank(relation.get("name"))
                && nonBlank(relation.get("relation"));
    }

    private void requireValidExamples(MarkdownNote frontmatter, List<Diagnostic> diagnostics) {
        if (frontmatter.structuredField("examples") == MarkdownNote.StructuredField.NON_LIST) {
            diagnostics.add(Diagnostic.blocking("examples", "concepts/concept examples must be a list."));
            return;
        }
        if (frontmatter.listOfScalars("examples").stream().anyMatch(example -> !nonBlank(example))) {
            diagnostics.add(Diagnostic.blocking(
                    "examples", "concepts/concept examples entries must be non-blank strings."));
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
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
            diagnostics.add(Diagnostic.blocking(key, "concepts/concept has no " + key + "."));
        }
        return value;
    }
}
