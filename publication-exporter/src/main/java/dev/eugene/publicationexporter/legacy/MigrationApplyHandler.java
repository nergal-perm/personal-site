package dev.eugene.publicationexporter.legacy;

import dev.eugene.publicationexporter.bridge.PublicationIdentity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.time.Instant;
import java.util.Optional;

public final class MigrationApplyHandler {

    private final LegacyWorkspaceInventoryHandler workspaceInventory;
    private final LegacyMigrationDecisionValidator decisionValidator;
    private final MigrationJournalStore journal;
    private final MigrationCatalogStore catalog;
    private final MigrationWorkspace workspace;
    private final SemanticOperationLock lock;
    private final Optional<ActivationMarkerStore> markerStore;

    public MigrationApplyHandler(LegacyWorkspaceInventoryHandler inventory,
            LegacyMigrationDecisionCodec codec, MigrationJournalStore journal,
            MigrationCatalogStore catalog, MigrationWorkspace workspace, SemanticOperationLock lock) {
        this(inventory, new LegacyMigrationDecisionValidator(inventory, codec), journal, catalog, workspace, lock,
                Optional.empty());
    }

    public MigrationApplyHandler(LegacyWorkspaceInventoryHandler inventory,
            LegacyMigrationDecisionCodec codec, MigrationJournalStore journal,
            MigrationCatalogStore catalog, MigrationWorkspace workspace, SemanticOperationLock lock,
            ActivationMarkerStore marker) {
        this(inventory, new LegacyMigrationDecisionValidator(inventory, codec), journal, catalog, workspace, lock,
                Optional.of(Objects.requireNonNull(marker, "marker")));
    }

    public MigrationApplyHandler(LegacyWorkspaceInventoryHandler inventory,
            LegacyMigrationDecisionValidator decisions, MigrationJournalStore journal,
            MigrationCatalogStore catalog, MigrationWorkspace workspace, SemanticOperationLock lock) {
        this(inventory, decisions, journal, catalog, workspace, lock, Optional.empty());
    }

    private MigrationApplyHandler(LegacyWorkspaceInventoryHandler inventory,
            LegacyMigrationDecisionValidator decisions, MigrationJournalStore journal,
            MigrationCatalogStore catalog, MigrationWorkspace workspace, SemanticOperationLock lock,
            Optional<ActivationMarkerStore> marker) {
        this.workspaceInventory = Objects.requireNonNull(inventory, "inventory");
        this.decisionValidator = Objects.requireNonNull(decisions, "decisions");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.markerStore = Objects.requireNonNull(marker, "marker");
    }

    public MigrationGeneration apply(String humanDecisionJson) {
        Objects.requireNonNull(humanDecisionJson, "humanDecisionJson");
        return lock.exclusively(() -> applyInsideLock(humanDecisionJson));
    }

    public MigrationGeneration rollForward() {
        return lock.exclusively(this::rollForwardInsideLock);
    }

    public MigrationGeneration rollBack() {
        return lock.exclusively(this::rollBackInsideLock);
    }

    private MigrationGeneration applyInsideLock(String humanDecisionJson) {
        LegacyWorkspaceInventory current = validateBeforeMutation(humanDecisionJson);
        MigrationGeneration started = plannedGeneration(current);
        preflightNewGeneration(started);
        MigrationPreimage preimage = startGeneration(started);
        return sealGeneration(applyRemainingIdentities(started, preimage));
    }

    private LegacyWorkspaceInventory validateBeforeMutation(String humanDecisionJson) {
        LegacyWorkspaceInventory current = workspaceInventory.inspect();
        decisionValidator.validate(humanDecisionJson, current);
        if (!current.ambiguities().isEmpty() || !current.blockers().isEmpty()) {
            throw new LegacyMigrationDecisionException(
                    "Migration inventory contains blockers or ambiguities and cannot be applied.");
        }
        return current;
    }

    private MigrationGeneration plannedGeneration(LegacyWorkspaceInventory current) {
        return new MigrationGeneration(
                current.inventorySha256(), allIdentities(current), 0, MigrationState.RUNNING);
    }

    private MigrationPreimage startGeneration(MigrationGeneration generation) {
        MigrationPreimage preimage = workspace.capture(generation);
        journal.save(generation, preimage);
        return preimage;
    }

    private MigrationGeneration applyRemainingIdentities(
            MigrationGeneration generation, MigrationPreimage preimage) {
        MigrationGeneration current = generation;
        while (current.completedSteps() < current.identities().size()) {
            workspace.apply(current, preimage, current.completedSteps());
            current = current.advance();
            journal.save(current);
        }
        return current;
    }

    private MigrationGeneration sealGeneration(MigrationGeneration generation) {
        MigrationGeneration sealed = generation.sealed();
        catalog.save(sealed);
        journal.save(sealed);
        publishMarker(sealed);
        return sealed;
    }

    private MigrationGeneration rollForwardInsideLock() {
        MigrationGeneration recorded = journal.read().orElseThrow(
                () -> new MigrationRecoveryException("No migration journal is available for recovery."));
        preflightTargets(recorded);
        if (recorded.isSealed()) {
            workspace.verify(recorded, recordedPreimage(recorded));
            MigrationGeneration recordedCatalog = catalog.read().orElseThrow(
                    () -> new MigrationRecoveryException("Sealed migration has no matching catalog generation."));
            if (!sameGeneration(recorded, recordedCatalog)) {
                throw new MigrationRecoveryException("Sealed migration catalog does not match its journal generation.");
            }
            publishMarker(recorded);
            return recorded;
        }
        MigrationGeneration running = runningGeneration(recorded);
        MigrationPreimage preimage = recordedPreimage(running);
        workspace.verify(running, preimage);
        return sealGeneration(applyRemainingIdentities(running, preimage));
    }

    private MigrationGeneration rollBackInsideLock() {
        MigrationGeneration recorded = journal.read().orElseThrow(
                () -> new MigrationRecoveryException("No migration journal is available for recovery."));
        preflightTargets(recorded);
        if (recorded.isRolledBack()) {
            clearMarker();
            return recorded;
        }
        MigrationGeneration running = runningGeneration(recorded);
        MigrationPreimage preimage = recordedPreimage(running);
        workspace.restore(preimage);
        MigrationGeneration rolledBack = running.rolledBack();
        catalog.save(rolledBack);
        journal.save(rolledBack);
        clearMarker();
        return rolledBack;
    }

    private MigrationGeneration runningGeneration(MigrationGeneration generation) {
        if (!generation.isRunning()) {
            throw new MigrationRecoveryException("Only a running migration generation can be recovered.");
        }
        return generation;
    }

    private void publishMarker(MigrationGeneration generation) {
        markerStore.ifPresent(store -> store.save(
                new ActivationMarker(1, generation.inventorySha256(), Instant.now())));
    }

    private void clearMarker() {
        markerStore.ifPresent(ActivationMarkerStore::clear);
    }

    private static boolean sameGeneration(MigrationGeneration first, MigrationGeneration second) {
        return first.inventorySha256().equals(second.inventorySha256())
                && first.identities().equals(second.identities())
                && first.completedSteps() == second.completedSteps()
                && first.state() == second.state();
    }

    private MigrationPreimage recordedPreimage(MigrationGeneration running) {
        return journal.preimage().filter(value -> value.belongsTo(running))
                .orElseThrow(() -> new MigrationRecoveryException(
                        "Migration journal has no immutable preimage for recovery."));
    }

    private void preflightNewGeneration(MigrationGeneration generation) {
        preflightTargets(generation);
        if (journal.exists() || catalog.exists() || markerStore.filter(ActivationMarkerStore::exists).isPresent()) {
            throw new MigrationRecoveryException(
                    "Migration artifacts already exist; recover or remove the partial generation before applying.");
        }
    }

    private void preflightTargets(MigrationGeneration generation) {
        lock.preflight();
        journal.preflight();
        catalog.preflight();
        markerStore.ifPresent(ActivationMarkerStore::preflight);
        workspace.preflight(generation);
    }

    private static List<PublicationIdentity> allIdentities(LegacyWorkspaceInventory current) {
        rejectDuplicates(current.approvedPairs(), "approvedPairs");
        rejectDuplicates(current.candidatePairs(), "candidatePairs");
        Set<PublicationIdentity> identities = new LinkedHashSet<>();
        identities.addAll(current.approvedPairs());
        identities.addAll(current.candidatePairs());
        List<PublicationIdentity> ordered = new ArrayList<>(identities);
        ordered.sort(Comparator.comparing(PublicationIdentity::toString));
        return ordered;
    }

    private static void rejectDuplicates(List<PublicationIdentity> identities, String name) {
        if (new java.util.HashSet<>(identities).size() != identities.size()) {
            throw new LegacyMigrationDecisionException(name + " contains duplicate identities.");
        }
    }
}
