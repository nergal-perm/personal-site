package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.candidate.CandidateSnapshot;
import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;
import dev.eugene.publicationexporter.reference.PublicField;
import dev.eugene.publicationexporter.site.BracketIndexedFields;
import dev.eugene.publicationexporter.site.YamlScalar;

import java.util.List;

public interface PublicationKind {

    String collection();

    String contentType();

    String routePrefix();

    AdmittedPublication admit(MarkdownNote frontmatter);

    KindContract contract();

    default ManagedArtifact projectManagedArtifact(
            PublicationIdentity identity, CandidateSnapshot approved, String locale) {
        return ManagedArtifact.of(
                markdownRelativePath(identity, locale),
                markdownContent(identity, approved, locale),
                markdownCollisionMarkerLine(identity));
    }

    private static String markdownRelativePath(PublicationIdentity identity, String locale) {
        return "src/content/" + identity.publicCollection() + "/" + locale + "/" + identity.publicId() + ".md";
    }

    private static String markdownCollisionMarkerLine(PublicationIdentity identity) {
        return "contentType: " + YamlScalar.doubleQuoted(identity.publicContentType());
    }

    private static String markdownContent(PublicationIdentity identity, CandidateSnapshot approved, String locale) {
        boolean isRu = "ru".equals(locale);
        StringBuilder yaml = new StringBuilder("---\n");
        appendYamlString(yaml, "id", identity.publicId());
        List<PublicField> fields = isRu ? approved.ruFields() : approved.enFields();
        yaml.append(BracketIndexedFields.render(fields, field -> appendYamlString(yaml, field.key(), field.value())));
        yaml.append("publish: true\n");
        appendYamlString(yaml, "contentType", identity.publicContentType());
        appendYamlString(yaml, "language", locale);
        appendYamlString(yaml, "sourceLanguage", "ru");
        appendYamlString(yaml, "sourceHash", approved.referenceMap().ruHash());
        appendYamlString(yaml, "translationStatus", isRu ? "source" : "generated");
        if (!isRu) {
            appendYamlString(yaml, "translationOf", identity.publicId());
        }
        if (!approved.structuredData().isBlank()) {
            yaml.append(approved.structuredData());
        }
        yaml.append("---\n");
        String body = isRu ? approved.ruBody() : approved.enBody();
        return yaml.append(body).toString();
    }

    private static void appendYamlString(StringBuilder yaml, String key, String value) {
        yaml.append(key).append(": ").append(YamlScalar.doubleQuoted(value)).append('\n');
    }
}
