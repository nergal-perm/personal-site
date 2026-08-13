package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.contract.FieldContract;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class CuratedPagePublicationKind implements PublicationKind {

    private static final Pattern PUBLIC_ID_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> KNOWN_PAGE_KEYS = Set.of(
            "about", "home", "essays", "claims", "notes", "music", "library", "concepts", "now");
    private static final String SUPPORTED_PAGE_KEY = "about";

    @Override
    public String collection() {
        return "editorial";
    }

    @Override
    public String contentType() {
        return "curated_page";
    }

    @Override
    public ManagedArtifact projectManagedArtifact(
            PublicationIdentity identity, CandidateSnapshot approved, String locale) {
        boolean isRu = "ru".equals(locale);
        List<PublicField> fields = isRu ? approved.ruFields() : approved.enFields();
        String json = CuratedPageJson.render(identity, fields, approved.structuredData(), locale);
        String collisionMarkerLine = json.lines()
                .filter(line -> line.contains("\"contentType\""))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Curated page JSON has no contentType collision marker"));
        return ManagedArtifact.of(
                "src/data/pages/" + locale + "/" + identity.publicId() + ".json",
                json,
                collisionMarkerLine);
    }

    @Override
    public String routePrefix() {
        return null;
    }

    @Override
    public AdmittedPublication admit(MarkdownNote note) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        String publicId = requireValidPublicId(note, diagnostics);
        String editorialPage = requireSupportedPageKey(note, diagnostics);
        if (publicId != null && editorialPage != null && !publicId.equals(editorialPage)) {
            diagnostics.add(Diagnostic.blocking("publicId", "editorial/curated_page publicId must equal editorialPage."));
        }
        String sourceId = requireNonBlank(note, "id", diagnostics);
        String title = requireNonBlank(note, "title", diagnostics);
        boolean searchable = publicSearchable(note, diagnostics);
        AboutPageBody body = parseBodyOrRecordDiagnostic(note, diagnostics);

        if (!diagnostics.isEmpty()) {
            return AdmittedPublication.blocked(diagnostics);
        }
        return AdmittedPublication.accepted(
                this,
                PublicationIdentity.of(collection(), contentType(), publicId),
                sourceId,
                translatedFields(title, body),
                structuredDataFrom(searchable));
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
                        FieldContract.allowedValue("editorialPage", FieldContract.Type.STRING, SUPPORTED_PAGE_KEY),
                        FieldContract.nonBlank("id"),
                        FieldContract.nonBlank("title")),
                List.of(FieldContract.optionalBoolean("publicSearchable")),
                List.of(),
                List.of(
                        "## Кратко (summary)",
                        "## Eyebrow (eyebrow)",
                        "## Лид (lead)",
                        "## Принципы with at least one ### subsection (principles)",
                        "## Колофон (colophon)"));
    }

    private List<PublicField> translatedFields(String title, AboutPageBody body) {
        List<PublicField> fields = new ArrayList<>();
        fields.add(PublicField.of("title", title));
        fields.add(PublicField.of("summary", body.summary()));
        fields.add(PublicField.of("eyebrow", body.eyebrow()));
        fields.add(PublicField.of("lead", body.lead()));
        for (int index = 0; index < body.principles().size(); index++) {
            AboutPageBody.Principle principle = body.principles().get(index);
            fields.add(PublicField.of("principles[" + index + "].title", principle.title()));
            fields.add(PublicField.of("principles[" + index + "].text", principle.text()));
        }
        fields.add(PublicField.of("colophon", body.colophon()));
        return List.copyOf(fields);
    }

    private String structuredDataFrom(boolean searchable) {
        return "{\"searchable\":" + searchable + ",\"type\":\"about\"}";
    }

    private boolean publicSearchable(MarkdownNote note, List<Diagnostic> diagnostics) {
        if (note.structuredField("publicSearchable") == MarkdownNote.StructuredField.ABSENT) {
            return false;
        }
        Optional<Boolean> value = note.booleanValue("publicSearchable");
        if (value.isEmpty()) {
            diagnostics.add(Diagnostic.blocking("publicSearchable", "must be a YAML boolean"));
            return false;
        }
        return value.get();
    }

    private AboutPageBody parseBodyOrRecordDiagnostic(MarkdownNote note, List<Diagnostic> diagnostics) {
        try {
            return AboutPageBody.parse(note.body());
        } catch (AboutPageBody.MalformedBodyException malformed) {
            diagnostics.add(Diagnostic.blocking("body", malformed.getMessage()));
            return null;
        }
    }

    private String requireSupportedPageKey(MarkdownNote note, List<Diagnostic> diagnostics) {
        String editorialPage = note.string("editorialPage").orElse(null);
        if (editorialPage == null || !KNOWN_PAGE_KEYS.contains(editorialPage)) {
            diagnostics.add(Diagnostic.blocking("editorialPage",
                    "must be one of: " + String.join(", ", KNOWN_PAGE_KEYS.stream().sorted().toList())));
            return null;
        }
        if (!editorialPage.equals(SUPPORTED_PAGE_KEY)) {
            diagnostics.add(Diagnostic.blocking("editorialPage",
                    "'" + editorialPage + "' is a known page type but only '" + SUPPORTED_PAGE_KEY
                            + "' is supported in this exporter edition."));
            return null;
        }
        return editorialPage;
    }

    private String requireValidPublicId(MarkdownNote note, List<Diagnostic> diagnostics) {
        String publicId = note.string("publicId").filter(this::isSlug).orElse(null);
        if (publicId == null) {
            diagnostics.add(Diagnostic.blocking("publicId", "must be a lowercase route slug"));
        }
        return publicId;
    }

    private boolean isSlug(String candidate) {
        return PUBLIC_ID_SLUG.matcher(candidate).matches();
    }

    private String requireNonBlank(MarkdownNote note, String key, List<Diagnostic> diagnostics) {
        String value = note.string(key).filter(candidate -> !candidate.isBlank()).orElse(null);
        if (value == null) {
            diagnostics.add(Diagnostic.blocking(key, "editorial/curated_page has no " + key + "."));
        }
        return value;
    }
}
