package dev.eugene.publicationexporter.contract;

import dev.eugene.publicationexporter.admission.PublicationKinds;

public final class PublicationContractWriter {

    public PublicationContract write() {
        return PublicationContract.of(1, PublicationKinds.installed().sortedContracts());
    }
}
