package dev.eugene.publicationexporter.legacy;

import java.util.Objects;
import java.util.function.Supplier;
import java.nio.file.Path;

public interface SemanticOperationLock {

    default void preflight() {
    }

    <T> T exclusively(Supplier<T> operation);

    static SemanticOperationLock createNull() {
        return new NullSemanticOperationLock();
    }

    static SemanticOperationLock create(Path reviewRoot) {
        return new FilesystemSemanticOperationLock(reviewRoot);
    }
}
