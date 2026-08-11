package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.contract.KindContract;
import dev.eugene.publicationexporter.note.MarkdownNote;

public interface PublicationKind {

    String collection();

    String contentType();

    String routePrefix();

    AdmittedPublication admit(MarkdownNote frontmatter);

    KindContract contract();
}
