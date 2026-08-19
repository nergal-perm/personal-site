package dev.eugene.publicationexporter.legacy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Supplier;

final class FilesystemSemanticOperationLock implements SemanticOperationLock {
    private final Path lockFile;
    private final Path root;

    FilesystemSemanticOperationLock(Path reviewRoot) {
        this.root = FilesystemMigrationPath.safeRoot(Objects.requireNonNull(reviewRoot, "reviewRoot"));
        lockFile = this.root.resolve(".migration/semantic-operation.lock");
    }

    @Override
    public void preflight() {
        FilesystemMigrationPath.requireDirectoryOrAbsent(root, lockFile.getParent());
        FilesystemMigrationPath.requireRegularFileOrAbsent(root, lockFile);
    }

    @Override
    public <T> T exclusively(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try {
            FilesystemMigrationPath.requireSafe(root, lockFile);
            Files.createDirectories(lockFile.getParent());
            preflight();
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException collision) { throw new SemanticOperationInProgressException(); }
                if (lock == null) throw new SemanticOperationInProgressException();
                try (lock) { return operation.get(); }
            }
        } catch (IOException error) { throw new MigrationRecoveryException("Cannot acquire filesystem migration lock: " + error.getMessage()); }
    }
}
