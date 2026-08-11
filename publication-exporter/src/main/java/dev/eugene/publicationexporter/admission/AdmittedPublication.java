package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.bridge.Diagnostic;
import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.List;
import java.util.Objects;

public final class AdmittedPublication {

    private final PublicationKind kind;
    private final PublicationIdentity identity;
    private final String sourceId;
    private final String title;
    private final String description;
    private final String structuredData;
    private final List<Diagnostic> diagnostics;

    private AdmittedPublication(PublicationKind kind, PublicationIdentity identity, String sourceId,
            String title, String description, String structuredData, List<Diagnostic> diagnostics) {
        this.kind = kind;
        this.identity = identity;
        this.sourceId = sourceId;
        this.title = title;
        this.description = description;
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
        return new AdmittedPublication(
                Objects.requireNonNull(kind, "kind"),
                Objects.requireNonNull(identity, "identity"),
                Objects.requireNonNull(sourceId, "sourceId"),
                Objects.requireNonNull(title, "title"),
                Objects.requireNonNull(description, "description"),
                Objects.requireNonNull(structuredData, "structuredData"),
                List.of());
    }

    public static AdmittedPublication blocked(List<Diagnostic> diagnostics) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("blocked() requires at least one diagnostic");
        }
        return new AdmittedPublication(null, null, null, null, null, null, diagnostics);
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
        return title;
    }

    public String description() {
        return description;
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
                && Objects.equals(sourceId, that.sourceId) && Objects.equals(title, that.title)
                && Objects.equals(description, that.description)
                && Objects.equals(structuredData, that.structuredData) && diagnostics.equals(that.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, identity, sourceId, title, description, structuredData, diagnostics);
    }

    @Override
    public String toString() {
        return "AdmittedPublication[kind=" + kind + ", identity=" + identity + ", sourceId=" + sourceId
                + ", title=" + title + ", description=" + description + ", structuredData=" + structuredData
                + ", diagnostics=" + diagnostics + "]";
    }
}
