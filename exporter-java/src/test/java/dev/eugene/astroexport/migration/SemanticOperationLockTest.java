package dev.eugene.astroexport.migration;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

final class SemanticOperationLockTest {
  @TempDir
  Path temp;

  @Test
  void twoSharedLeasesCoexistAndBlockExclusiveUntilClosed() throws Exception {
    try (SemanticOperationLock.Lease first = SemanticOperationLock.acquireShared(temp);
         SemanticOperationLock.Lease second = SemanticOperationLock.acquireShared(temp)) {
      assertThrows(SemanticOperationLock.LockBusyException.class,
          () -> SemanticOperationLock.acquireExclusive(temp));
    }

    try (SemanticOperationLock.Lease exclusive = SemanticOperationLock.acquireExclusive(temp)) {
      // Acquiring after both shared leases close proves the file lock was released.
    }
  }

  @Test
  void exclusiveLeaseBlocksSharedUntilClosed() throws Exception {
    try (SemanticOperationLock.Lease exclusive = SemanticOperationLock.acquireExclusive(temp)) {
      assertThrows(SemanticOperationLock.LockBusyException.class,
          () -> SemanticOperationLock.acquireShared(temp));
    }

    try (SemanticOperationLock.Lease shared = SemanticOperationLock.acquireShared(temp)) {
      // Acquiring after the exclusive lease closes proves the file lock was released.
    }
  }
}
