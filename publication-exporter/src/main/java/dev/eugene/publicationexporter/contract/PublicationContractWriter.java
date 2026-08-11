package dev.eugene.publicationexporter.contract;

import java.util.Comparator;
import java.util.List;

public final class PublicationContractWriter {

    public PublicationContract write() {
        List<KindContract> kinds = List.of(EssayPublicationContract.kind()).stream()
                .sorted(Comparator.comparing(KindContract::collection).thenComparing(KindContract::contentType))
                .toList();
        return PublicationContract.of(1, kinds);
    }
}
