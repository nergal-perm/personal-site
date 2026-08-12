package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;
import dev.eugene.publicationexporter.reference.PublicField;

import java.util.List;
import java.util.Objects;

public final class AdmittedPublication {

    private final PublicationKind kind;
    private final PublicationIdentity identity;
    private final String sourceId;
    private final List<PublicField> fields;
    private final String structuredData;
    private final List<Diagnostic> diagnostics;

    private AdmittedPublication(PublicationKind kind, PublicationIdentity identity, String sourceId,
            List<PublicField> fields, String structuredData, List<Diagnostic> diagnostics) {
        this.kind = kind;
        this.identity = identity;
        this.sourceId = sourceId;
        this.fields = List.copyOf(fields);
        this.structuredData = structuredData;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public static AdmittedPublication accepted(
            PublicationKind kind, PublicationIdentity identity, String sourceId, String title, String description) {
        return accepted(kind, identity, sourceId, title, description, "");
    }

    public static AdmittedPublication accepted(
            PublicationKind kind, PublicationIdentity identity, String sourceId, String title, String description,
            String structuredData) {
        return accepted(
                kind,
                identity,
                sourceId,
                List.of(PublicField.of("title", title), PublicField.of("description", description)),
                structuredData);
    }

    public static AdmittedPublication accepted(
            PublicationKind kind, PublicationIdentity identity, String sourceId,
            List<PublicField> fields, String structuredData) {
        return new AdmittedPublication(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(sourceId, "sourceId"),
                Objects.requireNonNull(fields, "fields"),
                Objects.requireNonNull(structuredData, "structuredData"),
                List.of());
    }

    public static AdmittedPublication blocked(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("blocked() requires at least one diagnostic");
        }
        return new AdmittedPublication(null, null, null, List.of(), "", diagnostics);
    }

    public boolean accepted() {
        return diagnostics.isEmpty();
    }

    public PublicationKind kind() {
        return kind;
    }

    public PublicationIdentity identity() {
        return identity;
    }

    public String sourceId() {
        return sourceId;
    }

    public String title() {
        return requiredField("title");
    }

    public String description() {
        return requiredField("description");
    }

    public List<PublicField> fields() {
        return fields;
    }

    public String structuredData() {
        return structuredData;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AdmittedPublication that)) {
            return false;
        }
        return Objects.equals(kind, that.kind) && Objects.equals(identity, that.identity)
                && Objects.equals(sourceId, that.sourceId) && fields.equals(that.fields)
                && Objects.equals(structuredData, that.structuredData) && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, identity, sourceId, fields, structuredData, diagnostics);
    }

    @Override
    public String toString() {
        return "AdmittedPublication[kind=" + kind + ", identity=" + identity + ", sourceId=" + sourceId
                + ", fields=" + fields + ", structuredData=" + structuredData
                + ", diagnostics=" + diagnostics + "]";
    }

    private String requiredField(String key) {
        return PublicField.value(fields, key)
                .orElseThrow(() -> new IllegalStateException("Admitted publication has no " + key + " field."));
    }
}
