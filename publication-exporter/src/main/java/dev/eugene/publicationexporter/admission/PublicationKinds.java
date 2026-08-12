package dev.eugene.publicationexporter.admission;

import dev.eugene.publicationexporter.contract.KindContract;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PublicationKinds {

    private final List<PublicationKind> kinds;

    private PublicationKinds(List<PublicationKind> kinds) {
        this.kinds = List.copyOf(kinds);
    }

    public static PublicationKinds installed() {
        return new PublicationKinds(
                List.of(
                        new EssayPublicationKind(),
                        new NotePublicationKind(),
                        new ClaimPublicationKind(),
                        new BookPublicationKind()));
    }

    public Optional<PublicationKind> forIdentity(String collection, String contentType) {
        return kinds.stream()
                .filter(kind -> kind.collection().equals(collection) && kind.contentType().equals(contentType))
                .findFirst();
    }

    public List<KindContract> sortedContracts() {
        return kinds.stream()
                .map(PublicationKind::contract)
                .sorted(Comparator.comparing(KindContract::collection).thenComparing(KindContract::contentType))
                .toList();
    }
}
