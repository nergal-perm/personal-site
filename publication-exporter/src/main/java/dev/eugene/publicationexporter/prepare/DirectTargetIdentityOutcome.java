package dev.eugene.publicationexporter.prepare;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

sealed interface DirectTargetIdentityOutcome permits IdentityAdmitted, IdentityBlocked {

    static DirectTargetIdentityOutcome admitted() {
        return new IdentityAdmitted();
    }

    static DirectTargetIdentityOutcome blocked(String reason) {
        return new IdentityBlocked(reason);
    }

    <T> T resolve(Supplier<T> onAdmitted, Function<String, T> onBlocked);
}

final class IdentityAdmitted implements DirectTargetIdentityOutcome {

    @Override
    public <T> T resolve(Supplier<T> onAdmitted, Function<String, T> onBlocked) {
        Objects.requireNonNull(onAdmitted, "onAdmitted");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onAdmitted.get();
    }
}

final class IdentityBlocked implements DirectTargetIdentityOutcome {

    private final String reason;

    IdentityBlocked(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public <T> T resolve(Supplier<T> onAdmitted, Function<String, T> onBlocked) {
        Objects.requireNonNull(onAdmitted, "onAdmitted");
        Objects.requireNonNull(onBlocked, "onBlocked");
        return onBlocked.apply(reason);
    }
}
