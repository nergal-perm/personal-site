package dev.eugene.publicationexporter.vault;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

public sealed interface AssetLookup permits FoundAsset, AmbiguousAsset, UnsafeAsset, AssetNotFound {

    static AssetLookup found(byte[] content) {
        return new FoundAsset(content);
    }

    static AssetLookup ambiguous() {
        return new AmbiguousAsset();
    }

    static AssetLookup unsafe() {
        return new UnsafeAsset();
    }

    static AssetLookup notFound() {
        return new AssetNotFound();
    }

    <T> T resolve(
            Function<byte[], T> onFound,
            Supplier<T> onAmbiguous,
            Supplier<T> onUnsafe,
            Supplier<T> onNotFound);
}

final class FoundAsset implements AssetLookup {

    private final byte[] content;

    FoundAsset(byte[] content) {
        this.content = Objects.requireNonNull(content, "content").clone();
    }

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onFound.apply(content.clone());
    }
}

final class AmbiguousAsset implements AssetLookup {

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onAmbiguous.get();
    }
}

final class UnsafeAsset implements AssetLookup {

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onUnsafe.get();
    }
}

final class AssetNotFound implements AssetLookup {

    @Override
    public <T> T resolve(
            Function<byte[], T> onFound, Supplier<T> onAmbiguous, Supplier<T> onUnsafe, Supplier<T> onNotFound) {
        Objects.requireNonNull(onFound, "onFound");
        Objects.requireNonNull(onAmbiguous, "onAmbiguous");
        Objects.requireNonNull(onUnsafe, "onUnsafe");
        Objects.requireNonNull(onNotFound, "onNotFound");
        return onNotFound.get();
    }
}
