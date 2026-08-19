package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class NullSemanticOperationLock implements SemanticOperationLock {

    private final AtomicBoolean held;

    public NullSemanticOperationLock() {
        this(false);
    }

    public NullSemanticOperationLock(boolean initiallyHeld) {
        held = new AtomicBoolean(initiallyHeld);
    }

    @Override
    public <T> T exclusively(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (!held.compareAndSet(false, true)) {
            throw new SemanticOperationInProgressException();
        }
        try {
            return operation.get();
        } finally {
            held.set(false);
        }
    }

    public void lock() {
        if (!held.compareAndSet(false, true)) {
            throw new SemanticOperationInProgressException();
        }
    }

    public void unlock() {
        held.set(false);
    }
}
