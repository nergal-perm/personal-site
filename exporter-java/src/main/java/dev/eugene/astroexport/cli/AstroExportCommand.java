package dev.eugene.astroexport.cli;

import dev.eugene.astroexport.assets.AssetResolver;
import dev.eugene.astroexport.discovery.PublicationDiscovery;
import dev.eugene.astroexport.fs.JnaFileDescriptor;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.manifest.ManifestBuilder;
import dev.eugene.astroexport.migration.SemanticSchemaState;
import dev.eugene.astroexport.migration.SemanticOperationLock;
import dev.eugene.astroexport.migration.ReferenceMigrationInventory;
import dev.eugene.astroexport.migration.SemanticOutputSafety;
import dev.eugene.astroexport.migration.SemanticDecisionDraftWriter;
import dev.eugene.astroexport.migration.SemanticMigrationService;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.PublicationKind;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.prepare.PrepareWorkflow;
import dev.eugene.astroexport.references.PageReferenceMap;
import dev.eugene.astroexport.references.PageReferenceMapCodec;
import dev.eugene.astroexport.release.ApprovedReleaseException;
import dev.eugene.astroexport.release.ApprovedReleaseMaterializer;
import dev.eugene.astroexport.report.ReportBuilder;
import dev.eugene.astroexport.review.PublishedSnapshotStore;
import dev.eugene.astroexport.review.ReviewLaunchPlanner;
import dev.eugene.astroexport.review.ReviewWorkspace;
import dev.eugene.astroexport.translation.TranslationValidator;
import dev.eugene.astroexport.validation.PublicationDiagnostic;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

@Command(
    name = "astro-export",
    mixinStandardHelpOptions = true,
    description = "Export explicitly published Obsidian notes into Astro source trees.",
    subcommands = {
        AstroExportCommand.BuildFromReviewCommand.class,
        AstroExportCommand.MigrateOverridesCommand.class,
        AstroExportCommand.PrepareCommand.class,
        AstroExportCommand.InspectPublicationCommand.class,
        AstroExportCommand.MarkReviewedCommand.class,
        AstroExportCommand.MigrateSemanticLinksCommand.class,
        AstroExportCommand.RefreshPublicationQueueCommand.class,
        AstroExportCommand.WritePublicationContractCommand.class,
    })
public final class AstroExportCommand implements Callable<Integer> {
  private static final Pattern PUBLIC_ID = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
  private static final List<String> REQUIRED_ASTRO_ROOT_FILES = List.of(
      "package.json",
      "src/content.config.ts",
      "scripts/check-content.mjs");
  private static final List<String> REFRESH_SUMMARY_STATUSES = List.of(
      "metadata_blocked",
      "translating",
      "ready_for_review",
      "ready_to_publish",
      "translation_failed",
      "stale");

  private final CommandServices services;

  @Spec
  CommandSpec spec;

  @Option(names = "--vault", description = "Obsidian vault root")
  Path vault;

  @Option(names = "--out", description = "Astro project root for atomic write mode")
  Path out;

  @Option(names = "--dry-run", description = "select and report without writing content")
  boolean dryRun;

  @Option(names = "--report", description = "report path")
  Path report = CommandServices.DEFAULT_REPORT_PATH;

  @Option(names = "--review", description = "translation review workspace")
  Path review = CommandServices.DEFAULT_REVIEW_ROOT;

  public AstroExportCommand() {
    this(CommandServices.defaults());
  }

  public AstroExportCommand(CommandServices services) {
    this.services = services;
  }

  public static CommandLine commandLine(AstroExportCommand command) {
    return new PropagatingCommandLine(command);
  }

  private static final class PropagatingCommandLine extends CommandLine {
    PropagatingCommandLine(AstroExportCommand command) {
      super(command);
    }

    @Override
    public int execute(String... args) {
      try {
        ParseResult parseResult = parseArgs(args);
        Integer helpExitCode = CommandLine.executeHelpRequest(parseResult);
        if (helpExitCode != null) {
          return helpExitCode;
        }
        return getExecutionStrategy().execute(parseResult);
      } catch (ParameterException error) {
        try {
          return getParameterExceptionHandler().handleParseException(error, args);
        } catch (RuntimeException handlerError) {
          throw handlerError;
        } catch (Exception handlerError) {
          throw new ExecutionException(this, handlerError.getMessage(), handlerError);
        }
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof RuntimeException runtime) {
          throw runtime;
        }
        if (cause instanceof Error fatal) {
          throw fatal;
        }
        throw error;
      }
    }
  }

  @Override
  public Integer call() {
    if (vault == null) {
      err().println("the following arguments are required: --vault");
      return 2;
    }
    return runExport(vault, out, dryRun, report, review, !dryRun);
  }

  private int runExport(
      Path vaultRoot,
      Path outRoot,
      boolean dryRunMode,
      Path reportPath,
      Path reviewRoot,
      boolean refreshRuReview) {
    try {
      validateReportOutSeparation(outRoot, reportPath);
    } catch (CliValidationException error) {
      printReport(ReportBuilder.buildBlockedWriteReport(error, null, null), error);
      return 1;
    }

    if (dryRunMode) {
      SelectionResult selection = null;
      try {
        selection = services.select(vaultRoot);
        ManifestResult manifest = prepareBilingualManifest(selection, reviewRoot, false, vaultRoot, false);
        String text = ReportBuilder.buildManifestReport(selection, manifest);
        return emitReport(reportPath, text, null) ? 0 : 1;
      } catch (ManifestBuilder.ManifestValidationException
          | ManifestBuilder.ManifestTransclusionException
          | TranslationValidator.TranslationValidationException error) {
        String text = buildBlockedManifestReport(selection, error);
        return emitReport(reportPath, text, error) ? 1 : 1;
      } catch (Exception error) {
        String text = ReportBuilder.buildBlockedWriteReport(error, selection, null);
        return emitReport(reportPath, text, error) ? 1 : 1;
      }
    }

    SelectionResult selection = null;
    ManifestResult manifest = null;
    Path siteRoot;
    ApprovedReleaseMaterializer.MaterializedRelease release = null;
    SemanticOperationLock.Lease semanticLease = null;
    try {
      siteRoot = validatedAstroRoot(outRoot);
      semanticLease = SemanticOperationLock.acquireShared(reviewRoot);
      SemanticSchemaState.Mode schemaMode = SemanticSchemaState.mode(reviewRoot);
      if (schemaMode == SemanticSchemaState.Mode.MIGRATION_INCOMPLETE) {
        throw new ApprovedReleaseException(
            "migration-incomplete",
            null,
            "semantic link migration is incomplete");
      }
      if (schemaMode == SemanticSchemaState.Mode.SEMANTIC) {
        selection = services.select(vaultRoot);
        release = services.buildApprovedRelease(vaultRoot, reviewRoot);
        manifest = resolveReleaseAssets(release.manifest(), vaultRoot);
      } else {
        selection = services.select(vaultRoot);
        manifest = prepareBilingualManifest(selection, reviewRoot, refreshRuReview, vaultRoot, true);
      }
    } catch (SemanticOperationLock.LockBusyException error) {
      closeQuietly(semanticLease);
      String text = ReportBuilder.buildBlockedWriteReport(
          new ApprovedReleaseException(
              "migration-incomplete",
              null,
              "semantic link migration is running; retry after it finishes"),
          selection,
          manifest);
      emitReport(reportPath, text, error);
      return 1;
    } catch (Exception error) {
      closeQuietly(semanticLease);
      String text = ReportBuilder.buildBlockedWriteReport(error, selection, manifest);
      emitReport(reportPath, text, error);
      return 1;
    }

    SiteWriter.WriteResult result;
    try {
      SiteWriter.CommitGuard commitGuard = release == null
          ? SiteWriter.CommitGuard.noop()
          : release.inputGuard()::verify;
      result = services.writeSite(siteRoot, manifest, release, services.astroGate(siteRoot), commitGuard);
    } catch (SiteWriter.WriterException error) {
      String text = error.committed()
          ? ReportBuilder.buildCommittedWriteErrorReport(error, selection, manifest, null)
          : ReportBuilder.buildBlockedWriteReport(error, selection, manifest);
      emitReport(reportPath, text, error);
      closeQuietly(semanticLease);
      return 1;
    } catch (Exception error) {
      String text = ReportBuilder.buildBlockedWriteReport(error, selection, manifest);
      emitReport(reportPath, text, error);
      closeQuietly(semanticLease);
      return 1;
    } finally {
      closeQuietly(semanticLease);
    }

    String text;
    try {
      text = release == null
          ? ReportBuilder.buildWriteReport(selection, manifest, result)
          : ReportBuilder.buildWriteReport(selection, manifest, result, release.ignoredDrafts());
    } catch (Exception error) {
      String committed = ReportBuilder.buildCommittedWriteErrorReport(
          new SiteWriter.WriterException(error.getMessage() == null ? error.toString() : error.getMessage(), true, List.of()),
          selection,
          manifest,
          result);
      emitReport(reportPath, committed, error);
      return 1;
    }
    try {
      writeReportFile(reportPath, text);
    } catch (IOException error) {
      CliValidationException reportError = new CliValidationException(
          "could not write report " + reportPath + ": " + error.getMessage());
      String committed = ReportBuilder.buildCommittedWriteErrorReport(
          new SiteWriter.WriterException(reportError.getMessage(), true, List.of()),
          selection,
          manifest,
          result);
      printReport(committed, reportError);
      return 1;
    }
    printReport(text, null);
    return 0;
  }

  private ManifestResult resolveReleaseAssets(ManifestResult manifest, Path vaultRoot) {
    return new ManifestResult(
        manifest.entries(),
        manifest.englishEntries(),
        manifest.retainedLinks(),
        manifest.strippedLinks(),
        manifest.assets(),
        new AssetResolver().resolveAssets(vaultRoot, manifest.assets()),
        manifest.translationUses());
  }

  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // Lease lifetime has ended.
    }
  }

  private ManifestResult prepareBilingualManifest(
      SelectionResult selection,
      Path reviewRoot,
      boolean writeRuReview,
      Path vaultRoot,
      boolean resolveAssets) {
    ManifestResult russian = services.buildRussianManifest(selection);
    if (writeRuReview) {
      russian.entries().forEach(entry -> services.writeRuReview(reviewRoot, entry));
    }
    ManifestResult english = services.buildEnglishManifest(russian, reviewRoot);
    return new ManifestResult(
        russian.entries(),
        english.entries(),
        russian.retainedLinks(),
        russian.strippedLinks(),
        russian.assets(),
        resolveAssets ? new AssetResolver().resolveAssets(vaultRoot, russian.assets()) : russian.resolvedAssets(),
        english.translationUses());
  }

  private void emitJson(BridgeResponse response) {
    out().println(response.toJson());
  }

  private BridgeResponse.Builder bridge(String command, boolean ok, String status) {
    return BridgeResponse.builder(command).ok(ok).status(status);
  }

  private PublicationIdentity identityFromEntry(ManifestEntry entry, Path reviewRoot) {
    Target target = target(entry);
    return new PublicationIdentity(target.collection(), target.publicId(),
        boundedReviewDirectory(reviewRoot, target.collection(), target.publicId()));
  }

  private PublicationIdentity identityFromPreflight(CommandServices.CliPreflight preflight, Path reviewRoot) {
    if (preflight == null) {
      return null;
    }
    if (preflight.entry() != null) {
      return identityFromEntry(preflight.entry(), reviewRoot);
    }
    Note note = preflight.note();
    if (note == null) {
      return null;
    }
    String collection = text(note.frontmatter().get("publicCollection"));
    String publicId = text(note.frontmatter().get("publicId"));
    if (collection == null
        || publicId == null
        || !PublicationKind.allowedCollections().contains(collection)
        || PublicationKind.allowedContentTypes(collection).isEmpty()
        || !PUBLIC_ID.matcher(publicId).matches()) {
      return null;
    }
    return new PublicationIdentity(collection, publicId, boundedReviewDirectory(reviewRoot, collection, publicId));
  }

  private ReviewPairState reviewPairState(ManifestEntry entry, Path reviewRoot) {
    Target target = target(entry);
    Path directory = boundedReviewDirectory(reviewRoot, target.collection(), target.publicId());
    if (directory == null) {
      return new ReviewPairState("invalid", null, "English review directory escapes the review root.", null);
    }
    return reviewPairState(entry, reviewRoot, directory.resolve("en.md"));
  }

  private ReviewPairState reviewPairState(ManifestEntry entry, Path reviewRoot, Path english) {
    Target target = target(entry);
    byte[] snapshot;
    try {
      snapshot = readSafeRegularFile(english);
    } catch (java.io.FileNotFoundException error) {
      return new ReviewPairState("missing", null, "English review is missing; run prepare.", null);
    } catch (IllegalArgumentException error) {
      return new ReviewPairState("invalid", null, error.getMessage(), null);
    } catch (IOException error) {
      return new ReviewPairState("invalid", null,
          "English review cannot be inspected: " + error.getClass().getSimpleName() + ".", null);
    }
    String content;
    try {
      content = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(snapshot))
          .toString();
    } catch (CharacterCodingException error) {
      return new ReviewPairState("invalid", null, "English review must be valid UTF-8.", null);
    }
    try {
      Path validationRoot = Files.createTempDirectory("astro-export-pair-validation-");
      try {
        Path validationPath = validationRoot.resolve(target.collection()).resolve(target.publicId()).resolve("en.md");
        Files.createDirectories(validationPath.getParent());
        Files.writeString(validationPath, content, StandardCharsets.UTF_8);
        ManifestResult englishManifest = services.buildEnglishManifest(
            new ManifestResult(List.of(entry), List.of(), List.of(), List.of()),
            validationRoot);
        String status = text(englishManifest.entries().getFirst().metadata().get("translationStatus"));
        if (!Set.of("generated", "reviewed").contains(status)) {
          return new ReviewPairState("invalid", null, "English review has an invalid translationStatus.", null);
        }
        if (!Arrays.equals(readSafeRegularFile(english), snapshot)) {
          return new ReviewPairState("stale", null,
              "English review changed during validation; inspect it again.", null);
        }
        return new ReviewPairState("fresh", status, "", snapshot);
      } finally {
        deleteTreeBestEffort(validationRoot);
      }
    } catch (TranslationValidator.TranslationValidationException error) {
      String reason = error.reason();
      String freshness = reason.contains("sourceHash does not match") || reason.contains("stale review")
          ? "stale"
          : "invalid";
      return new ReviewPairState(freshness, null, reason, null);
    } catch (IOException | IllegalArgumentException error) {
      return new ReviewPairState("invalid", null, "English review is invalid: " + error.getMessage(), null);
    }
  }

  private static byte[] readSafeRegularFile(Path path) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new java.io.FileNotFoundException(path.toString());
    }
    try (JnaFileDescriptor descriptor = JnaFileDescriptor.openReadNoFollow(path)) {
      JnaFileDescriptor.Snapshot snapshot = descriptor.snapshot();
      if (!snapshot.attributes().isRegularFile()) {
        throw new IllegalArgumentException("English review must be a regular file.");
      }
      if (snapshot.linkCount() != 1) {
        throw new IllegalArgumentException("English review must not have multiple hard links.");
      }
      return descriptor.readAllBytes();
    }
  }

  private static byte[] reboundReferences(
      byte[] russian,
      byte[] english,
      byte[] references) {
    PageReferenceMap candidateMap = PageReferenceMapCodec.read(
        references, "candidate/references.json");
    PageReferenceMap reviewedMap = candidateMap.withHashes(
        PageReferenceMapCodec.sha256(russian),
        PageReferenceMapCodec.sha256(english));
    byte[] reviewedReferences = PageReferenceMapCodec.write(reviewedMap);
    PageReferenceMapCodec.validate(reviewedMap, russian, english);
    return reviewedReferences;
  }

  private static boolean candidateTripleMatches(
      Path candidate,
      byte[] russian,
      byte[] english,
      byte[] references) {
    try {
      return Arrays.equals(readSafeRegularFile(candidate.resolve("ru.md")), russian)
          && Arrays.equals(readSafeRegularFile(candidate.resolve("en.md")), english)
          && Arrays.equals(
              readSafeRegularFile(candidate.resolve("references.json")),
              references);
    } catch (IOException | IllegalArgumentException error) {
      return false;
    }
  }

  private static void replaceCandidateWithReviewed(
      Path reviewRoot,
      ManifestEntry entry,
      byte[] candidateRu,
      byte[] candidateEn,
      byte[] reviewedEn,
      byte[] candidateReferences,
      byte[] sourceSnapshot,
      Path sourcePath,
      Path candidate) throws IOException {
    byte[] reviewedReferences =
        reboundReferences(candidateRu, reviewedEn, candidateReferences);
    try (ReviewWorkspace.PendingCandidateSnapshot pending =
             ReviewWorkspace.stageCandidateSnapshot(
                 reviewRoot, entry, candidateRu, reviewedEn, reviewedReferences)) {
      WorkflowStateService.SnapshotGuard sourceGuard =
          new WorkflowStateService.SnapshotGuard(sourcePath, sourceSnapshot);
      pending.commit(
          List.of(
              sourceGuard,
              new WorkflowStateService.SnapshotGuard(candidate.resolve("ru.md"), candidateRu),
              new WorkflowStateService.SnapshotGuard(candidate.resolve("en.md"), candidateEn),
              new WorkflowStateService.SnapshotGuard(
                  candidate.resolve("references.json"), candidateReferences)),
          List.of(sourceGuard));
    }
  }

  private StablePreflight stablePreflight(Path vaultRoot, String note, Path source) throws IOException {
    byte[] before = Files.readAllBytes(source);
    CommandServices.CliPreflight current = services.preflight(vaultRoot, note);
    byte[] after = Files.readAllBytes(source);
    if (!Arrays.equals(before, after)) {
      throw new WorkflowStateService.ConcurrentFileUpdateException("source note changed during preflight");
    }
    return new StablePreflight(current, after);
  }

  private boolean setWorkflowIfChanged(
      CommandServices.CliPreflight preflight,
      String status,
      String translationStatus,
      String diagnostic,
      byte[] expectedContent,
      List<WorkflowStateService.SnapshotGuard> guards) throws IOException {
    Note note = preflight.note();
    if (note == null || !Boolean.TRUE.equals(note.frontmatter().get("publish"))) {
      return false;
    }
    String existingTranslation = text(note.frontmatter().get("publicTranslationStatus"));
    if (existingTranslation != null && existingTranslation.isEmpty()) {
      existingTranslation = null;
    }
    String existingDiagnostic = text(note.frontmatter().get("publicWorkflowDiagnostic"));
    if (existingDiagnostic == null) {
      existingDiagnostic = "";
    }
    if (status.equals(note.frontmatter().get("publicWorkflowStatus"))
        && java.util.Objects.equals(existingTranslation, translationStatus)
        && existingDiagnostic.equals(diagnostic)) {
      return false;
    }
    ArrayList<WorkflowStateService.SnapshotGuard> allGuards = new ArrayList<>();
    if (expectedContent != null) {
      allGuards.add(new WorkflowStateService.SnapshotGuard(note.path(), expectedContent));
    }
    allGuards.addAll(guards);
    if (!snapshotsCurrent(note.path(), expectedContent, guards)) {
      throw new WorkflowStateService.ConcurrentFileUpdateException(
          "publication changed before workflow state update");
    }
    services.workflowState().updateWorkflowState(
        note.path(),
        new WorkflowStateService.WorkflowUpdate(status, translationStatus, diagnostic),
        services.clock().instant(),
        allGuards.toArray(WorkflowStateService.SnapshotGuard[]::new));
    return true;
  }

  private int inspect(Path vaultRoot, String note, Path reviewRoot) {
    CommandServices.CliPreflight preflight = services.preflight(vaultRoot, note);
    PublicationIdentity identity = identityFromPreflight(preflight, reviewRoot);
    if (!preflight.ready()) {
      emitJson(bridge("inspect-publication", false, "metadata_blocked")
          .note(note)
          .identity(identity)
          .diagnostics(preflight.diagnostics())
          .workspaceHealth(preflight.workspaceHealth())
          .build());
      return 1;
    }
    ReviewPairState pair = reviewPairState(preflight.entry(), reviewRoot);
    SemanticSchemaState.Mode schemaMode = SemanticSchemaState.mode(reviewRoot);
    String candidateState = candidateState(preflight.entry(), pair, identity, reviewRoot, schemaMode);
    ReleaseInspection releaseInspection = releaseInspection(vaultRoot, reviewRoot, schemaMode);
    boolean fresh = "fresh".equals(pair.freshness());
    ReviewLaunchPlanner.ReviewPlan reviewPlan = null;
    if (fresh) {
      try {
        reviewPlan = new ReviewLaunchPlanner().plan(
            reviewRoot,
            identity.reviewDirectory(),
            preflight.entry(),
            pair.content());
      } catch (ReviewLaunchPlanner.ReviewLaunchException error) {
        emitJson(bridge("inspect-publication", false, error.status())
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                error.field(), error.getMessage())))
            .workspaceHealth(preflight.workspaceHealth())
            .pairFreshness(pair.freshness())
            .translationStatus(pair.translationStatus())
            .candidateState(candidateState)
            .approvedSnapshotState(releaseInspection.approvedSnapshotState())
            .semanticReferencesState(releaseInspection.semanticReferencesState())
            .releaseState(releaseInspection.releaseState())
            .build());
        return 1;
      }
    }
    emitJson(bridge("inspect-publication", fresh,
            fresh ? freshPairWorkflowStatus(pair.translationStatus()) : "stale")
        .note(note)
        .identity(identity)
        .diagnostics(fresh ? List.of() : List.of(new PublicationDiagnostic("translation", pair.diagnostic())))
        .workspaceHealth(preflight.workspaceHealth())
        .pairFreshness(pair.freshness())
        .translationStatus(pair.translationStatus())
        .candidateState(candidateState)
        .approvedSnapshotState(releaseInspection.approvedSnapshotState())
        .semanticReferencesState(releaseInspection.semanticReferencesState())
        .releaseState(releaseInspection.releaseState())
        .reviewPlan(reviewPlan)
        .build());
    return fresh ? 0 : 1;
  }

  private int migrateSemanticLinks(
      Path vaultRoot,
      Path reviewRoot,
      Path astroRoot,
      Path reportPath,
      Path decisionsPath,
      boolean apply,
      boolean rollForward,
      boolean rollBack,
      Path draftPath,
      boolean validate) {
    int modes = (apply ? 1 : 0) + (rollForward ? 1 : 0) + (rollBack ? 1 : 0);
    if (draftPath != null && (modes > 0 || validate)) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "draft", "--draft is available only in read-only inventory mode.", true)))
          .build());
      return 1;
    }
    if (validate && modes > 0) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "migration",
              "--validate cannot be combined with --apply, --roll-forward, or --roll-back.",
              true)))
          .build());
      return 1;
    }
    if (modes > 1) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "migration",
              "Require exactly one of normal inventory, --apply, --roll-forward, or --roll-back.",
              true)))
          .build());
      return 1;
    }
    if ((rollForward || rollBack) && reviewRoot == null) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "review",
              "Recovery requires --review with migration-v1.journal.json and recovery paths.",
              true)))
          .build());
      return 1;
    }
    if (!rollForward && !rollBack
        && (vaultRoot == null || reviewRoot == null || astroRoot == null || reportPath == null)) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "migration",
              "Inventory and apply modes require --vault, --review, --astro, and --report.",
              true)))
          .build());
      return 1;
    }
    if (apply && decisionsPath == null) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "decisions",
              "--apply requires --decisions.",
              true)))
          .build());
      return 1;
    }
    if (validate && decisionsPath == null) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "decisions", "--validate requires --decisions.", true)))
          .build());
      return 1;
    }
    if (!rollForward && !rollBack
        && (astroRoot == null || !Files.isDirectory(astroRoot, LinkOption.NOFOLLOW_LINKS))) {
      emitJson(bridge("migrate-semantic-links", false, "metadata_blocked")
          .diagnostics(List.of(new PublicationDiagnostic(
              "astro",
              "Astro root must be an existing directory.",
              true)))
          .build());
      return 1;
    }
    try {
      if (!rollForward && !rollBack) {
        SemanticOutputSafety.preflight(reportPath, reviewRoot, "inventory report");
        if (draftPath != null) {
          SemanticOutputSafety.preflight(draftPath, reviewRoot, "draft path");
        }
      }
      if (rollForward || rollBack) {
        services.recoverSemanticMigration(new SemanticMigrationService.RecoveryRequest(
            reviewRoot,
            rollForward
                ? SemanticMigrationService.RecoveryMode.ROLL_FORWARD
                : SemanticMigrationService.RecoveryMode.ROLL_BACK));
        emitJson(bridge("migrate-semantic-links", true,
                rollForward ? "rolled-forward" : "rolled-back")
            .build());
        return 0;
      }
      if (apply) {
        services.applySemanticMigration(new SemanticMigrationService.ApplyRequest(
            vaultRoot,
            reviewRoot,
            astroRoot,
            reportPath,
            decisionsPath,
            services.astroGate(astroRoot)));
        emitJson(bridge("migrate-semantic-links", true, "applied")
            .build());
        return 0;
      }
      ReferenceMigrationInventory.Inventory inventory =
          services.inspectReferenceMigration(vaultRoot, reviewRoot, astroRoot, reportPath);
      ReferenceMigrationInventory.Summary summary = inventory.summary();
      if (validate) {
        services.validateReferenceMigration(inventory, decisionsPath);
        emitJson(bridge("migrate-semantic-links", true, "validated")
            .summary(summary.toPayload())
            .build());
        return 0;
      }
      if (draftPath != null) {
        new SemanticDecisionDraftWriter().write(draftPath, inventory, reviewRoot);
        emitJson(bridge("migrate-semantic-links", true, "draft-written")
            .summary(summary.toPayload())
            .build());
        return 0;
      }
      boolean ok = !summary.decisionsRequired();
      emitJson(bridge(
              "migrate-semantic-links",
              ok,
              ok ? "ready" : "decisions-required")
          .summary(summary.toPayload())
          .build());
      return ok ? 0 : 1;
    } catch (RuntimeException error) {
      emitJson(bridge("migrate-semantic-links", false, "unsafe-input")
          .diagnostics(List.of(new PublicationDiagnostic(
              "migration",
              (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())
                  + " journal=" + SemanticSchemaState.migrationJournal(reviewRoot)
                  + " recovery=" + reviewRoot.resolve(".semantic-links/recovery-v1"),
              true)))
          .build());
      return 1;
    }
  }

  private String candidateState(
      ManifestEntry entry,
      ReviewPairState pair,
      PublicationIdentity identity,
      Path reviewRoot,
      SemanticSchemaState.Mode schemaMode) {
    if (schemaMode == SemanticSchemaState.Mode.SEMANTIC && identity != null) {
      Path candidate = identity.reviewDirectory().resolve("candidate");
      if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        return "absent";
      }
      try {
        byte[] ru = readSafeRegularFile(candidate.resolve("ru.md"));
        byte[] en = readSafeRegularFile(candidate.resolve("en.md"));
        PageReferenceMapCodec.validate(
            PageReferenceMapCodec.read(
                readSafeRegularFile(candidate.resolve("references.json")),
                candidate.resolve("references.json").toString()),
            ru,
            en);
        return candidateState(reviewPairState(entry, reviewRoot, candidate.resolve("en.md")));
      } catch (IOException | RuntimeException error) {
        return "stale";
      }
    }
    return candidateState(pair);
  }

  private static String candidateState(ReviewPairState pair) {
    if (pair == null || "missing".equals(pair.freshness())) {
      return "absent";
    }
    if ("fresh".equals(pair.freshness()) && "reviewed".equals(pair.translationStatus())) {
      return "reviewed";
    }
    if ("fresh".equals(pair.freshness()) && "generated".equals(pair.translationStatus())) {
      return "generated";
    }
    return "stale";
  }

  private ReleaseInspection releaseInspection(
      Path vaultRoot,
      Path reviewRoot,
      SemanticSchemaState.Mode schemaMode) {
    return switch (schemaMode) {
      case LEGACY -> new ReleaseInspection("absent", "migration-required", "blocked");
      case MIGRATION_INCOMPLETE -> new ReleaseInspection("absent", "invalid", "blocked");
      case SEMANTIC -> semanticReleaseInspection(vaultRoot, reviewRoot);
    };
  }

  private ReleaseInspection semanticReleaseInspection(Path vaultRoot, Path reviewRoot) {
    try {
      services.buildApprovedRelease(vaultRoot, reviewRoot);
      return new ReleaseInspection("valid", "valid", "releasable");
    } catch (ApprovedReleaseException error) {
      String approvedState = switch (error.code()) {
        case "missing-approved-snapshot" -> "absent";
        default -> "invalid";
      };
      String semanticState = switch (error.code()) {
        case "missing-approved-snapshot" -> "valid";
        default -> "invalid";
      };
      return new ReleaseInspection(approvedState, semanticState, "blocked");
    } catch (RuntimeException error) {
      return new ReleaseInspection("invalid", "invalid", "blocked");
    }
  }

  private int markReviewed(Path vaultRoot, String note, Path reviewRoot, Path jobsRoot) {
    CommandServices.CliPreflight initial = services.preflight(vaultRoot, note);
    PublicationIdentity identity = identityFromPreflight(initial, reviewRoot);
    if (!initial.ready()) {
      List<PublicationDiagnostic> diagnostics = initial.diagnostics();
      String status = "metadata_blocked";
      if (initial.note() != null && Boolean.TRUE.equals(initial.note().frontmatter().get("publish"))) {
        try {
          StablePreflight stable = stablePreflight(vaultRoot, note, initial.note().path());
          if (stable.preflight().ready()) {
            throw new WorkflowStateService.ConcurrentFileUpdateException(
                "publication became ready during metadata reconciliation");
          }
          diagnostics = stable.preflight().diagnostics();
          identity = identityFromPreflight(stable.preflight(), reviewRoot);
          setWorkflowIfChanged(stable.preflight(), "metadata_blocked", null,
              workflowDiagnostic(diagnostics), stable.sourceSnapshot(), List.of());
        } catch (WorkflowStateService.ConcurrentFileUpdateException error) {
          status = "stale";
          diagnostics = List.of(new PublicationDiagnostic(
              "source",
              "Source changed while metadata state was being recorded; inspect it and retry."));
        } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException error) {
          diagnostics = append(diagnostics, new PublicationDiagnostic(
              "workflow",
              "Could not record metadata_blocked: " + error.getClass().getSimpleName() + "."));
        }
      }
      emitJson(bridge("mark-reviewed", false, status)
          .note(note)
          .identity(identity)
          .diagnostics(diagnostics)
          .workspaceHealth(initial.workspaceHealth())
          .build());
      return 1;
    }

    Target target = target(initial.entry());
    Path lockPath = jobsRoot.resolve(target.collection()).resolve(target.publicId() + ".lock");
    try (LockHandle ignored = acquireLock(lockPath)) {
      StablePreflight stable;
      try {
        stable = stablePreflight(vaultRoot, note, initial.note().path());
      } catch (WorkflowStateService.ConcurrentFileUpdateException error) {
        emitJson(bridge("mark-reviewed", false, "stale")
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                "source", "Source changed while review validation started; inspect it and retry.")))
            .workspaceHealth(initial.workspaceHealth())
            .build());
        return 1;
      }
      PublicationIdentity lockedIdentity = identityFromPreflight(stable.preflight(), reviewRoot);
      if (!stable.preflight().ready() || !identity.samePublicIdentity(lockedIdentity)) {
        emitJson(bridge("mark-reviewed", false, "stale")
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                "source",
                "Publication collection or id changed before review could be marked; inspect the current note again.")))
            .workspaceHealth(stable.preflight().workspaceHealth())
            .build());
        return 1;
      }

      SemanticSchemaState.Mode schemaMode = SemanticSchemaState.mode(reviewRoot);
      if (schemaMode == SemanticSchemaState.Mode.MIGRATION_INCOMPLETE) {
        emitJson(bridge("mark-reviewed", false, "stale")
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                "semantic-links",
                "Semantic link migration is incomplete; recover it before approving review.")))
            .workspaceHealth(stable.preflight().workspaceHealth())
            .build());
        return 1;
      }
      boolean semanticApproval = schemaMode == SemanticSchemaState.Mode.SEMANTIC;
      Path candidate = identity.reviewDirectory().resolve("candidate");
      byte[] candidateRu = null;
      byte[] candidateEn = null;
      byte[] candidateReferences = null;
      String semanticPageRef = null;
      SemanticOperationLock.Lease semanticLease = null;
      if (semanticApproval) {
        try {
          semanticLease = SemanticOperationLock.acquireShared(reviewRoot);
          candidateRu = readSafeRegularFile(candidate.resolve("ru.md"));
          candidateEn = readSafeRegularFile(candidate.resolve("en.md"));
          candidateReferences = readSafeRegularFile(candidate.resolve("references.json"));
          PageReferenceMap candidateMap = PageReferenceMapCodec.read(
              candidateReferences, "candidate/references.json");
          PageReferenceMapCodec.validate(candidateMap, candidateRu, candidateEn);
          semanticPageRef = candidateMap.pageRef();
        } catch (SemanticOperationLock.LockBusyException error) {
          emitJson(bridge("mark-reviewed", false, "translating")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "job",
                  "Semantic link migration is running; review it after migration finishes.")))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .build());
          return 1;
        } catch (IOException | RuntimeException error) {
          if (semanticLease != null) {
            semanticLease.close();
          }
          emitJson(bridge("mark-reviewed", false, "stale")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "translation",
                  "Semantic candidate snapshot is unavailable: "
                      + error.getClass().getSimpleName() + ": " + error.getMessage())))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .build());
          return 1;
        }
      }

      ReviewPairState pair = semanticApproval
          ? reviewPairState(stable.preflight().entry(), reviewRoot, candidate.resolve("en.md"))
          : reviewPairState(stable.preflight().entry(), reviewRoot);
      if (!"fresh".equals(pair.freshness())) {
        if (semanticLease != null) {
          semanticLease.close();
        }
        try {
          setWorkflowIfChanged(stable.preflight(), "stale", null, pair.diagnostic(),
              stable.sourceSnapshot(), List.of());
        } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException ignoredError) {
          // The stale bridge response below is the durable operator signal.
        }
        emitJson(bridge("mark-reviewed", false, "stale")
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic("translation", pair.diagnostic())))
            .workspaceHealth(stable.preflight().workspaceHealth())
            .pairFreshness(pair.freshness())
            .build());
        return 1;
      }

      String reviewed = null;
      byte[] reviewedBytes;
      if ("reviewed".equals(pair.translationStatus())) {
        reviewedBytes = pair.content();
      } else {
        try {
          reviewed = ReviewWorkspace.setReviewedStatusPreservingContent(
              decode(pair.content()));
          reviewedBytes = reviewed.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
          if (semanticLease != null) {
            semanticLease.close();
          }
          emitJson(bridge("mark-reviewed", false, "translation_failed")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "review",
                  "Could not mark English review as reviewed: "
                      + error.getClass().getSimpleName() + ": " + error.getMessage())))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .pairFreshness("fresh")
              .translationStatus(pair.translationStatus())
              .build());
          return 1;
        }
      }
      byte[] stagedRussian = ReviewWorkspace.renderRuReview(
          stable.preflight().entry()).getBytes(StandardCharsets.UTF_8);
      ReviewWorkspace.PendingPublishedSnapshot pendingSnapshot;
      try {
        pendingSnapshot = services.stageApprovedSnapshot(
            reviewRoot,
            target.collection(),
            target.publicId(),
            semanticApproval ? candidateRu : stagedRussian,
            reviewedBytes,
            semanticApproval ? reboundReferences(candidateRu, reviewedBytes, candidateReferences) : null);
      } catch (PublishedSnapshotStore.PublishedSnapshotRecoveryException error) {
        if (semanticLease != null) {
          semanticLease.close();
        }
        emitPublishedSnapshotRecovery(
            note,
            identity,
            stable.preflight().workspaceHealth(),
            error);
        return 1;
      } catch (RuntimeException error) {
        if (semanticLease != null) {
          semanticLease.close();
        }
        emitJson(bridge("mark-reviewed", false,
                freshPairWorkflowStatus(pair.translationStatus()))
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                "published-snapshot",
                "Could not stage the approved publication baseline ("
                    + error.getClass().getSimpleName()
                    + "); the previous baseline was not changed.")))
            .workspaceHealth(stable.preflight().workspaceHealth())
            .pairFreshness("fresh")
            .translationStatus(pair.translationStatus())
            .build());
        return 1;
      }
      Path english = englishPath(reviewRoot, target);
      boolean sourceApproved = false;
      boolean publishedApproved = false;
      try (pendingSnapshot; SemanticOperationLock.Lease ignoredLease = semanticLease) {
        StablePreflight beforeEnglishCommit = stablePreflight(vaultRoot, note, stable.preflight().note().path());
        if (!beforeEnglishCommit.preflight().ready()
            || !identity.samePublicIdentity(identityFromPreflight(beforeEnglishCommit.preflight(), reviewRoot))
            || !Arrays.equals(beforeEnglishCommit.sourceSnapshot(), stable.sourceSnapshot())) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "source changed before reviewed status commit");
        }
        if (semanticApproval && !candidateTripleMatches(candidate, candidateRu, candidateEn, candidateReferences)) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "candidate snapshot changed before reviewed status commit");
        }
        if (!semanticApproval && !Arrays.equals(readSafeRegularFile(english), pair.content())) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "source or English review changed before reviewed status commit");
        }
        if (!"reviewed".equals(pair.translationStatus())) {
          if (semanticApproval) {
            replaceCandidateWithReviewed(
                reviewRoot,
                stable.preflight().entry(),
                candidateRu,
                candidateEn,
                reviewedBytes,
                candidateReferences,
                beforeEnglishCommit.sourceSnapshot(),
                beforeEnglishCommit.preflight().note().path(),
                candidate);
            candidateEn = reviewedBytes;
            candidateReferences = reboundReferences(candidateRu, reviewedBytes, candidateReferences);
          } else {
            services.replaceEnglishReview(
                reviewRoot,
                reviewed,
                target.collection(),
                target.publicId(),
                pair.content(),
                List.of(new WorkflowStateService.SnapshotGuard(
                    beforeEnglishCommit.preflight().note().path(),
                    stable.sourceSnapshot())));
          }
        }
        StablePreflight current = stablePreflight(vaultRoot, note, beforeEnglishCommit.preflight().note().path());
        if (!current.preflight().ready()
            || !identity.samePublicIdentity(identityFromPreflight(current.preflight(), reviewRoot))
            || !Arrays.equals(current.sourceSnapshot(), stable.sourceSnapshot())) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "source changed before source commit");
        }
        if (semanticApproval && !candidateTripleMatches(candidate, candidateRu, reviewedBytes, candidateReferences)) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "candidate snapshot changed before source commit");
        }
        if (!semanticApproval && !Arrays.equals(readSafeRegularFile(english), reviewedBytes)) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "English review changed before source commit");
        }

        StablePreflight approved = stablePreflight(
            vaultRoot, note, current.preflight().note().path());
        if (!approved.preflight().ready()
            || !identity.samePublicIdentity(
                identityFromPreflight(approved.preflight(), reviewRoot))) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "source projection or English review changed before published snapshot commit");
        }
        byte[] approvedRussian = ReviewWorkspace.renderRuReview(
            approved.preflight().entry()).getBytes(StandardCharsets.UTF_8);
        if (!Arrays.equals(stagedRussian, approvedRussian)) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "source projection or English review changed before published snapshot commit");
        }
        if (semanticApproval && !candidateTripleMatches(candidate, candidateRu, reviewedBytes, candidateReferences)) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "candidate snapshot changed before published snapshot commit");
        }
        if (!semanticApproval && !Arrays.equals(readSafeRegularFile(english), reviewedBytes)) {
          throw new WorkflowStateService.ConcurrentFileUpdateException(
              "English review changed before published snapshot commit");
        }

        ReviewWorkspace.PublishedSnapshotResult published;
        try {
          List<WorkflowStateService.SnapshotGuard> snapshotGuards = semanticApproval
              ? List.of(
                  new WorkflowStateService.SnapshotGuard(
                      approved.preflight().note().path(),
                      approved.sourceSnapshot()),
                  new WorkflowStateService.SnapshotGuard(candidate.resolve("ru.md"), candidateRu),
                  new WorkflowStateService.SnapshotGuard(candidate.resolve("en.md"), reviewedBytes),
                  new WorkflowStateService.SnapshotGuard(
                      candidate.resolve("references.json"), candidateReferences))
              : List.of(
                  new WorkflowStateService.SnapshotGuard(
                      approved.preflight().note().path(),
                      approved.sourceSnapshot()),
                  new WorkflowStateService.SnapshotGuard(english, reviewedBytes));
          published = pendingSnapshot.commit(snapshotGuards);
          publishedApproved = true;
        } catch (PublishedSnapshotStore.ConcurrentPublishedSnapshotException error) {
          pendingSnapshot.close();
          CurrentPairState currentPair = currentPairStateAfterConflict(
              vaultRoot,
              note,
              stable.preflight().note().path(),
              reviewRoot,
              identity);
          BridgeResponse.Builder response = bridge("mark-reviewed", false, "stale")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "published-snapshot",
                  "Source or English review changed at the published snapshot "
                      + "commit boundary; inspect both and retry.")))
              .workspaceHealth(stable.preflight().workspaceHealth());
          if (currentPair != null) {
            response
                .pairFreshness(currentPair.freshness())
                .translationStatus(currentPair.translationStatus());
          }
          emitJson(response.build());
          return 1;
        } catch (PublishedSnapshotStore.PublishedSnapshotRecoveryException error) {
          throw error;
        } catch (RuntimeException error) {
          pendingSnapshot.close();
          emitJson(bridge("mark-reviewed", false, "ready_for_review")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "published-snapshot",
                  "English review is approved, but the published baseline was not "
                      + "updated (" + error.getClass().getSimpleName()
                      + "); invoke Mark current translation reviewed again.")))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .pairFreshness("fresh")
              .translationStatus("reviewed")
              .build());
          return 1;
        }

        setWorkflowIfChanged(current.preflight(), "ready_to_publish", "reviewed", "",
            stable.sourceSnapshot(),
            semanticApproval
                ? List.of(
                    new WorkflowStateService.SnapshotGuard(candidate.resolve("ru.md"), candidateRu),
                    new WorkflowStateService.SnapshotGuard(candidate.resolve("en.md"), reviewedBytes),
                    new WorkflowStateService.SnapshotGuard(
                        candidate.resolve("references.json"), candidateReferences))
                : List.of(new WorkflowStateService.SnapshotGuard(english, reviewedBytes)));
        sourceApproved = true;

        List<PublicationDiagnostic> snapshotDiagnostics =
            published.recoveryPaths().stream()
                .map(path -> new PublicationDiagnostic(
                    "published-snapshot-cleanup",
                    "Approved baseline was saved, but the displaced previous snapshot "
                        + "could not be removed; recovery path: " + path,
                    false))
                .toList();
        pendingSnapshot.close();

        BridgeResponse.Builder success = bridge("mark-reviewed", true, "ready_to_publish")
            .note(note)
            .identity(identity)
            .diagnostics(snapshotDiagnostics)
            .workspaceHealth(stable.preflight().workspaceHealth())
            .pairFreshness("fresh")
            .translationStatus("reviewed");
        Map<String, Integer> impactSummary = semanticApproval
            ? approvalImpactSummary(vaultRoot, reviewRoot, semanticPageRef)
            : null;
        if (impactSummary != null) {
          success.summary(impactSummary);
        }
        emitJson(success.build());
        return 0;
      } catch (PublishedSnapshotStore.PublishedSnapshotRecoveryException error) {
        emitPublishedSnapshotRecovery(
            note,
            identity,
            stable.preflight().workspaceHealth(),
            error);
        return 1;
      } catch (ReviewWorkspace.ConcurrentReviewUpdateException
          | WorkflowStateService.ConcurrentFileUpdateException error) {
        PublishedSnapshotStore.PublishedSnapshotRecoveryException recovery =
            findPublishedSnapshotRecovery(error);
        if (recovery != null) {
          emitPublishedSnapshotRecovery(
              note,
              identity,
              stable.preflight().workspaceHealth(),
              recovery);
          return 1;
        }
        if (publishedApproved) {
          emitJson(bridge("mark-reviewed", false, "ready_to_publish")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "workflow",
                  "Approved publication baseline was saved, but the source queue could not be updated ("
                      + error.getClass().getSimpleName()
                      + "); run Refresh publication queue.")))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .pairFreshness("fresh")
              .translationStatus("reviewed")
              .build());
          return 1;
        }
        CurrentPairState currentPair = currentPairStateAfterConflict(
            vaultRoot,
            note,
            stable.preflight().note().path(),
            reviewRoot,
            identity);
        BridgeResponse.Builder response = bridge("mark-reviewed", false, "stale")
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                "review",
                "Source or English review changed at the commit boundary; inspect both and retry."
                    + concurrentReviewRecovery(error))))
            .workspaceHealth(stable.preflight().workspaceHealth());
        if (currentPair != null) {
          response
              .pairFreshness(currentPair.freshness())
              .translationStatus(currentPair.translationStatus());
        }
        emitJson(response.build());
        return 1;
      } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException error) {
        PublishedSnapshotStore.PublishedSnapshotRecoveryException recovery =
            findPublishedSnapshotRecovery(error);
        if (recovery != null) {
          emitPublishedSnapshotRecovery(
              note,
              identity,
              stable.preflight().workspaceHealth(),
              recovery);
          return 1;
        }
        if (publishedApproved) {
          emitJson(bridge("mark-reviewed", false, "ready_to_publish")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "workflow",
                  "Approved publication baseline was saved, but the source queue could not be updated ("
                      + error.getClass().getSimpleName()
                      + "); run Refresh publication queue.")))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .pairFreshness("fresh")
              .translationStatus("reviewed")
              .build());
          return 1;
        }
        if (sourceApproved) {
          emitJson(bridge("mark-reviewed", false, "ready_to_publish")
              .note(note)
              .identity(identity)
              .diagnostics(List.of(new PublicationDiagnostic(
                  "published-snapshot",
                  "English review and source workflow are approved, but the "
                      + "published baseline was not updated ("
                      + error.getClass().getSimpleName()
                      + "); invoke Mark current translation reviewed again.")))
              .workspaceHealth(stable.preflight().workspaceHealth())
              .translationStatus("reviewed")
              .build());
          return 1;
        }
        emitJson(bridge("mark-reviewed", false, "ready_for_review")
            .note(note)
            .identity(identity)
            .diagnostics(List.of(new PublicationDiagnostic(
                "workflow",
                "English review is marked reviewed, but the source queue could not be updated ("
                    + error.getClass().getSimpleName() + "); run Refresh publication queue.")))
            .workspaceHealth(stable.preflight().workspaceHealth())
            .pairFreshness("fresh")
            .translationStatus("reviewed")
            .build());
        return 1;
      } catch (RuntimeException error) {
        PublishedSnapshotStore.PublishedSnapshotRecoveryException recovery =
            findPublishedSnapshotRecovery(error);
        if (recovery != null) {
          emitPublishedSnapshotRecovery(
              note,
              identity,
              stable.preflight().workspaceHealth(),
              recovery);
          return 1;
        }
        throw error;
      }
    } catch (LockBusyException error) {
      emitJson(bridge("mark-reviewed", false, "translating")
          .note(note)
          .identity(identity)
          .diagnostics(List.of(new PublicationDiagnostic(
              "job", "Translation is still running; review it after prepare finishes.")))
          .workspaceHealth(initial.workspaceHealth())
          .build());
      return 1;
    } catch (IOException error) {
      emitJson(bridge("mark-reviewed", false, "translation_failed")
          .note(note)
          .identity(identity)
          .diagnostics(List.of(new PublicationDiagnostic(
              "lock", "Could not acquire publication lock: " + error.getClass().getSimpleName() + ".")))
          .workspaceHealth(initial.workspaceHealth())
          .build());
      return 1;
    }
  }

  private int refresh(Path vaultRoot, Path reviewRoot, Path jobsRoot) {
    SelectionResult selection;
    try {
      selection = services.select(vaultRoot);
    } catch (PublicationDiscovery.PublicationSearchException | java.io.UncheckedIOException error) {
      return refreshBridgeIoFailure(error);
    }
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    selection.included().stream().map(Note::vaultPath).sorted().forEach(paths::add);
    selection.excluded().stream().map(SelectionResult.Exclusion::vaultPath).sorted().forEach(paths::add);
    LinkedHashMap<String, Integer> summary = emptySummary();
    int updated = 0;
    int unchanged = 0;
    int uncertain = 0;
    ArrayList<PublicationDiagnostic> errors = new ArrayList<>();
    for (String path : paths) {
      CommandServices.CliPreflight initial;
      try {
        initial = services.preflight(vaultRoot, path);
      } catch (java.io.UncheckedIOException error) {
        increment(summary, "metadata_blocked");
        unchanged++;
        errors.add(new PublicationDiagnostic(
            "io",
            path + ": could not read publication files (" + error.getClass().getSimpleName() + ")."));
        continue;
      }
      PublicationIdentity initialIdentity = identityFromPreflight(initial, reviewRoot);
      if (initial.note() == null) {
        increment(summary, "metadata_blocked");
        unchanged++;
        errors.addAll(initial.diagnostics());
        continue;
      }
      LockHandle lock = null;
      if (initialIdentity != null) {
        try {
          lock = acquireLock(jobsRoot.resolve(initialIdentity.collection()).resolve(initialIdentity.publicId() + ".lock"));
        } catch (LockBusyException error) {
          increment(summary, "translating");
          unchanged++;
          continue;
        } catch (IOException error) {
          increment(summary, "stale");
          unchanged++;
          errors.add(new PublicationDiagnostic(
              "lock",
              path + ": could not acquire publication lock (" + error.getClass().getSimpleName() + ")."));
          continue;
        }
      }
      try {
        StablePreflight stable;
        try {
          stable = stablePreflight(vaultRoot, path, initial.note().path());
        } catch (WorkflowStateService.ConcurrentFileUpdateException error) {
          increment(summary, "stale");
          unchanged++;
          errors.add(new PublicationDiagnostic(
              "source", path + ": source changed during reconciliation; retry refresh."));
          continue;
        } catch (IOException error) {
          increment(summary, "metadata_blocked");
          unchanged++;
          errors.add(new PublicationDiagnostic(
              "io", path + ": could not read publication files (" + error.getClass().getSimpleName() + ")."));
          continue;
        }
        PublicationIdentity currentIdentity = identityFromPreflight(stable.preflight(), reviewRoot);
        if (!sameIdentity(initialIdentity, currentIdentity)) {
          increment(summary, "stale");
          unchanged++;
          errors.add(new PublicationDiagnostic(
              "source", path + ": publication identity changed during reconciliation; retry refresh."));
          continue;
        }
        DerivedRefreshState state = deriveRefreshState(stable.preflight(), reviewRoot, jobsRoot);
        if (stable.preflight().note() == null
            || !Boolean.TRUE.equals(stable.preflight().note().frontmatter().get("publish"))) {
          increment(summary, state.status());
          unchanged++;
          errors.addAll(stable.preflight().diagnostics());
          continue;
        }
        try {
          boolean changed = setWorkflowIfChanged(stable.preflight(), state.status(),
              state.translationStatus(), state.diagnostic(), stable.sourceSnapshot(), state.guards());
          if (!changed && !snapshotsCurrent(stable.preflight().note().path(), stable.sourceSnapshot(), state.guards())) {
            throw new WorkflowStateService.ConcurrentFileUpdateException(
                "publication changed before reconciliation completed");
          }
          increment(summary, state.status());
          if (changed) {
            updated++;
          } else {
            unchanged++;
          }
        } catch (WorkflowStateService.ConcurrentFileUpdateException error) {
          if (error.committed()) {
            increment(summary, state.status());
            uncertain++;
          } else {
            increment(summary, "stale");
            unchanged++;
          }
          errors.add(new PublicationDiagnostic(
              "source",
              path + ": source or English review changed at the reconciliation boundary; retry refresh."
                  + concurrentWorkflowRecovery(error)));
        } catch (IOException | IllegalArgumentException | java.io.UncheckedIOException error) {
          increment(summary, state.status());
          unchanged++;
          errors.add(new PublicationDiagnostic(
              "workflow",
              path + ": could not reconcile workflow state ("
                  + error.getClass().getSimpleName() + ")."));
        }
      } finally {
        if (lock != null) {
          lock.close();
        }
      }
    }

    boolean ok = errors.isEmpty();
    emitJson(bridge("refresh-publication-queue", ok, ok ? "refreshed" : "refresh_failed")
        .diagnostics(errors)
        .summary(summary)
        .updated(updated)
        .unchanged(unchanged)
        .uncertain(uncertain)
        .build());
    return ok ? 0 : 1;
  }

  private int refreshBridgeIoFailure(RuntimeException error) {
    return bridgeIoFailure("refresh-publication-queue", "refresh_failed", null, error, true);
  }

  private int bridgeIoFailure(String command, String status, String note, RuntimeException error) {
    return bridgeIoFailure(command, status, note, error, false);
  }

  private int bridgeIoFailure(
      String command,
      String status,
      String note,
      RuntimeException error,
      boolean refreshSummary) {
    BridgeResponse.Builder response = bridge(command, false, status)
        .note(note)
        .diagnostics(List.of(new PublicationDiagnostic(
            "io", "Could not read publication files: " + error.getClass().getSimpleName() + ".")));
    if (refreshSummary) {
      response
          .summary(emptySummary())
          .updated(0)
          .unchanged(0)
          .uncertain(0);
    }
    emitJson(response.build());
    return 1;
  }

  private DerivedRefreshState deriveRefreshState(
      CommandServices.CliPreflight preflight,
      Path reviewRoot,
      Path jobsRoot) {
    if (!preflight.ready()) {
      return new DerivedRefreshState("metadata_blocked", null,
          workflowDiagnostic(preflight.diagnostics()), List.of());
    }
    Target target = target(preflight.entry());
    String currentStatus = text(preflight.note().frontmatter().get("publicWorkflowStatus"));
    ReviewPairState pair = reviewPairState(preflight.entry(), reviewRoot);
    if ("fresh".equals(pair.freshness())) {
      return new DerivedRefreshState(
          freshPairWorkflowStatus(pair.translationStatus()),
          pair.translationStatus(),
          "",
          List.of(new WorkflowStateService.SnapshotGuard(englishPath(reviewRoot, target), pair.content())));
    }
    String latestJob = latestJobState(jobsRoot, target, requiredTranslationSourceHash(preflight.entry()));
    if ("translating".equals(currentStatus) && "failed".equals(latestJob)) {
      return new DerivedRefreshState(
          "translation_failed",
          null,
          "The last translation job failed; inspect it and run prepare again.",
          List.of());
    }
    if ("translating".equals(currentStatus)) {
      return new DerivedRefreshState(
          "stale",
          null,
          "Translation was interrupted without a valid English review; run prepare again.",
          List.of());
    }
    return new DerivedRefreshState("stale", null, pair.diagnostic(), List.of());
  }

  private String latestJobState(Path jobsRoot, Target target, String sourceHash) {
    Path publicationRoot = jobsRoot.resolve(target.collection()).resolve(target.publicId());
    if (bounded(jobsRoot, publicationRoot) == null || !Files.isDirectory(publicationRoot, LinkOption.NOFOLLOW_LINKS)) {
      return null;
    }
    try (var paths = Files.list(publicationRoot)) {
      for (Path directory : paths.sorted(Comparator.reverseOrder()).toList()) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        Path journal = directory.resolve("job.json");
        if (!Files.isRegularFile(journal, LinkOption.NOFOLLOW_LINKS)) {
          continue;
        }
        Map<?, ?> payload = new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(Files.readString(journal, StandardCharsets.UTF_8), Map.class);
        if (Integer.valueOf(1).equals(payload.get("schemaVersion"))
            && directory.getFileName().toString().equals(payload.get("jobId"))
            && target.collection().equals(payload.get("collection"))
            && target.publicId().equals(payload.get("publicId"))
            && sourceHash.equals(payload.get("sourceHash"))
            && payload.get("state") instanceof String state) {
          return state;
        }
      }
    } catch (IOException | IllegalArgumentException ignored) {
      return null;
    }
    return null;
  }

  private LockHandle acquireLock(Path lockPath) throws IOException, LockBusyException {
    Files.createDirectories(lockPath.getParent());
    try {
      Files.writeString(lockPath, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    } catch (java.nio.file.FileAlreadyExistsException ignored) {
      // Existing lock files are expected.
    }
    JnaFileDescriptor descriptor = JnaFileDescriptor.openLockNoFollow(lockPath);
    try {
      JnaFileDescriptor.Snapshot snapshot = descriptor.snapshot();
      if (!snapshot.attributes().isRegularFile() || snapshot.linkCount() != 1) {
        throw new IOException("publication lock must be a regular file with one link");
      }
      if (!descriptor.tryExclusiveLock()) {
        throw new LockBusyException();
      }
      return new LockHandle(descriptor);
    } catch (IOException | LockBusyException error) {
      descriptor.close();
      throw error;
    }
  }

  private Path validatedAstroRoot(Path value) {
    if (value == null) {
      throw new CliValidationException("Write mode requires --out <Astro root>.");
    }
    Path root;
    try {
      root = value.toRealPath();
    } catch (IOException error) {
      throw new CliValidationException("invalid Astro root " + value + ": " + error.getMessage(), error);
    }
    if (!Files.isDirectory(root)) {
      throw new CliValidationException("invalid Astro root " + root + ": not a directory");
    }
    List<String> missing = REQUIRED_ASTRO_ROOT_FILES.stream()
        .filter(relative -> !Files.isRegularFile(root.resolve(relative)))
        .toList();
    if (!missing.isEmpty()) {
      throw new CliValidationException(
          "invalid Astro root " + root + ": missing required file(s): " + String.join(", ", missing));
    }
    return root;
  }

  private static void validateReportOutSeparation(Path outRoot, Path reportPath) {
    if (outRoot == null) {
      return;
    }
    Path resolvedOut = outRoot.toAbsolutePath().normalize();
    Path resolvedReport = reportPath.toAbsolutePath().normalize();
    try {
      if (Files.exists(outRoot, LinkOption.NOFOLLOW_LINKS)) {
        resolvedOut = outRoot.toRealPath();
      }
      Path existingReportParent = nearestExisting(reportPath.toAbsolutePath());
      Path remainder = existingReportParent.toAbsolutePath().normalize()
          .relativize(reportPath.toAbsolutePath().normalize());
      resolvedReport = existingReportParent.toRealPath().resolve(remainder).normalize();
    } catch (IOException | IllegalArgumentException ignored) {
      // Fall back to normalized lexical paths, matching Python's strict=False boundary.
    }
    if (resolvedReport.equals(resolvedOut) || resolvedReport.startsWith(resolvedOut)) {
      throw new CliValidationException(
          "--report must resolve outside --out: report " + resolvedReport + " is at or under " + resolvedOut);
    }
  }

  private static Path nearestExisting(Path path) {
    Path current = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? path : path.getParent();
    while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      current = current.getParent();
    }
    return current == null ? path.toAbsolutePath().getRoot() : current;
  }

  private static Path boundedReviewDirectory(Path reviewRoot, String collection, String publicId) {
    return bounded(reviewRoot, reviewRoot.resolve(collection).resolve(publicId));
  }

  private static Path bounded(Path root, Path candidate) {
    Path lexicalRoot = root.toAbsolutePath().normalize();
    Path lexicalCandidate = candidate.toAbsolutePath().normalize();
    if (!lexicalCandidate.startsWith(lexicalRoot)) {
      return null;
    }
    try {
      Path realRoot = Files.exists(root, LinkOption.NOFOLLOW_LINKS) ? root.toRealPath() : lexicalRoot;
      Path existing = nearestExisting(candidate.toAbsolutePath());
      Path realExisting = existing.toRealPath();
      Path relative = existing.toAbsolutePath().normalize().relativize(lexicalCandidate);
      Path resolved = realExisting.resolve(relative).normalize();
      return resolved.startsWith(realRoot) ? lexicalCandidate : null;
    } catch (IOException | IllegalArgumentException error) {
      return lexicalCandidate.startsWith(lexicalRoot) ? lexicalCandidate : null;
    }
  }

  private static void writeReportFile(Path path, String text) throws IOException {
    if (path.getParent() != null) {
      Files.createDirectories(path.getParent());
    }
    Files.writeString(path, text, StandardCharsets.UTF_8);
  }

  private boolean emitReport(Path path, String text, Exception error) {
    boolean written = true;
    try {
      writeReportFile(path, text);
    } catch (IOException reportError) {
      written = false;
      err().println("ERROR: could not write report " + path + ": " + reportError.getMessage());
    }
    printReport(text, error);
    return written;
  }

  private void printReport(String text, Exception error) {
    out().print(text);
    if (!text.endsWith("\n")) {
      out().println();
    }
    out().flush();
    if (error != null) {
      err().println("ERROR: " + error.getMessage());
    }
  }

  private PrintWriter out() {
    return spec.commandLine().getOut();
  }

  private PrintWriter err() {
    return spec.commandLine().getErr();
  }

  private static String buildBlockedManifestReport(SelectionResult selection, Exception error) {
    if (selection == null) {
      return ReportBuilder.buildBlockedWriteReport(error, null, null);
    }
    if (error instanceof TranslationValidator.TranslationValidationException translation) {
      return String.join("\n", List.of(
          "# Astro export dry-run",
          "",
          "## Summary",
          "",
          "- Files matched by `rg`: " + selection.matched(),
          "- Confirmed `publish: true` frontmatter: " + selection.confirmed(),
          "- Included by selector: " + selection.included().size(),
          "- Excluded by selector: " + selection.excluded().size(),
          "- Normalized RU records: " + selection.included().size(),
          "- Generated EN records: 0",
          "- Manifest blocked: 0",
          "- Translation blockers: 1",
          "",
          "## Translation blockers (1)",
          "",
          "- `" + translation.sourcePath() + "` — `" + translation.publicId() + "` — " + translation.reason(),
          ""));
    }
    String line;
    if (error instanceof ManifestBuilder.ManifestValidationException validation) {
      line = "- `" + validation.sourcePath() + "` — `" + validation.fieldName() + "` " + validation.reason();
    } else {
      String message = error.getMessage();
      int separator = message == null ? -1 : message.indexOf(": unpublished transclusion ");
      if (separator >= 0) {
        line = "- `" + message.substring(0, separator) + "` — unpublished transclusion `"
            + message.substring(separator + ": unpublished transclusion ".length()) + "`";
      } else {
        line = "- " + String.valueOf(message);
      }
    }
    return String.join("\n", List.of(
        "# Astro export dry-run",
        "",
        "## Summary",
        "",
        "- Files matched by `rg`: " + selection.matched(),
        "- Confirmed `publish: true` frontmatter: " + selection.confirmed(),
        "- Included by selector: " + selection.included().size(),
        "- Excluded by selector: " + selection.excluded().size(),
        "- Normalized RU records: 0",
        "- Manifest blocked: 1",
        "",
        "## Blocking errors (1)",
        "",
        line,
        ""));
  }

  private static String freshPairWorkflowStatus(String translationStatus) {
    return "reviewed".equals(translationStatus) ? "ready_to_publish" : "ready_for_review";
  }

  private static String workflowDiagnostic(List<PublicationDiagnostic> diagnostics) {
    return diagnostics.stream()
        .filter(PublicationDiagnostic::blocking)
        .map(diagnostic -> diagnostic.field() + ": " + diagnostic.message())
        .reduce((first, second) -> first + "; " + second)
        .orElse("");
  }

  private static String requiredTranslationSourceHash(ManifestEntry entry) {
    if (entry.translationSourceHash() != null && !entry.translationSourceHash().isBlank()) {
      return entry.translationSourceHash();
    }
    Object sourceHash = entry.metadata().get("sourceHash");
    return sourceHash == null ? "" : String.valueOf(sourceHash);
  }

  private static String decode(byte[] content) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(content))
          .toString();
    } catch (CharacterCodingException error) {
      throw new IllegalArgumentException("content must be valid UTF-8", error);
    }
  }

  private static Target target(ManifestEntry entry) {
    String targetPath = entry.targetPath();
    if (targetPath.startsWith("src/content/")) {
      String[] parts = targetPath.split("/");
      String publicId = parts[4].replaceFirst("\\.md$", "");
      return new Target(parts[2], publicId);
    }
    if (targetPath.startsWith("src/data/pages/ru/")) {
      String file = Path.of(targetPath).getFileName().toString();
      return new Target("editorial", file.replaceFirst("\\.json$", ""));
    }
    Object id = entry.metadata().get("id");
    return new Target("", id == null ? "" : String.valueOf(id));
  }

  private static Path englishPath(Path reviewRoot, Target target) {
    return reviewRoot.resolve(target.collection()).resolve(target.publicId()).resolve("en.md");
  }

  private static List<PublicationDiagnostic> append(
      List<PublicationDiagnostic> diagnostics,
      PublicationDiagnostic diagnostic) {
    ArrayList<PublicationDiagnostic> copy = new ArrayList<>(diagnostics);
    copy.add(diagnostic);
    return copy;
  }

  private static boolean snapshotsCurrent(
      Path source,
      byte[] sourceSnapshot,
      List<WorkflowStateService.SnapshotGuard> guards) {
    try {
      if (!Arrays.equals(Files.readAllBytes(source), sourceSnapshot)) {
        return false;
      }
      for (WorkflowStateService.SnapshotGuard guard : guards) {
        if (!Arrays.equals(Files.readAllBytes(guard.path()), guard.expectedContent())) {
          return false;
        }
      }
      return true;
    } catch (IOException error) {
      return false;
    }
  }

  private static LinkedHashMap<String, Integer> emptySummary() {
    LinkedHashMap<String, Integer> summary = new LinkedHashMap<>();
    REFRESH_SUMMARY_STATUSES.forEach(status -> summary.put(status, 0));
    return summary;
  }

  private static void increment(Map<String, Integer> summary, String status) {
    summary.put(status, summary.getOrDefault(status, 0) + 1);
  }

  private static boolean sameIdentity(PublicationIdentity first, PublicationIdentity second) {
    if (first == null || second == null) {
      return first == second;
    }
    return first.samePublicIdentity(second);
  }

  private static String text(Object value) {
    if (!(value instanceof String text)) {
      return null;
    }
    String stripped = text.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  private static String concurrentReviewRecovery(Exception error) {
    if (error instanceof ReviewWorkspace.ConcurrentReviewUpdateException reviewError) {
      ArrayList<String> details = new ArrayList<>();
      if (reviewError.committed()) {
        details.add("The replacement may already be live because automatic rollback could not be confirmed.");
      }
      if (reviewError.preservedPath() != null) {
        details.add("Recoverable bytes: " + reviewError.preservedPath() + ".");
      }
      return details.isEmpty() ? "" : " " + String.join(" ", details);
    }
    if (error instanceof WorkflowStateService.ConcurrentFileUpdateException workflowError) {
      return concurrentWorkflowRecovery(workflowError);
    }
    return "";
  }

  private static String publishedSnapshotRecoveryMessage(
      PublishedSnapshotStore.PublishedSnapshotRecoveryException error) {
    String paths = error.recoveryPaths().stream()
        .map(Path::toString)
        .collect(java.util.stream.Collectors.joining(", "));
    if (error.disposition()
        == PublishedSnapshotStore.RecoveryDisposition.CANDIDATE_VISIBLE) {
      return "Recovery ownership: CANDIDATE_VISIBLE. "
          + "Approved baseline ownership is uncertain: the candidate may be visible at "
          + error.publishedPath() + ", and recovery data remains at " + paths
          + ". Inspect these paths before any retry.";
    }
    return "Recovery ownership: STAGED_CANDIDATE. "
        + "Approved baseline cleanup is incomplete: an uncommitted staged candidate "
        + "remains at " + paths + " for published path " + error.publishedPath()
        + ". Inspect and resolve recovery data before any retry.";
  }

  private void emitPublishedSnapshotRecovery(
      String note,
      PublicationIdentity identity,
      List<PublicationDiagnostic> workspaceHealth,
      PublishedSnapshotStore.PublishedSnapshotRecoveryException error) {
    emitJson(bridge("mark-reviewed", false, "recovery_required")
        .note(note)
        .identity(identity)
        .diagnostics(List.of(new PublicationDiagnostic(
            "published-snapshot-recovery",
            publishedSnapshotRecoveryMessage(error))))
        .workspaceHealth(workspaceHealth)
        .build());
  }

  private static PublishedSnapshotStore.PublishedSnapshotRecoveryException
      findPublishedSnapshotRecovery(Throwable error) {
    if (error
        instanceof PublishedSnapshotStore.PublishedSnapshotRecoveryException
            recovery) {
      return recovery;
    }
    for (Throwable suppressed : error.getSuppressed()) {
      PublishedSnapshotStore.PublishedSnapshotRecoveryException recovery =
          findPublishedSnapshotRecovery(suppressed);
      if (recovery != null) {
        return recovery;
      }
    }
    return null;
  }

  private Map<String, Integer> approvalImpactSummary(
      Path vaultRoot,
      Path reviewRoot,
      String pageRef) {
    if (pageRef == null) {
      return null;
    }
    try {
      ApprovedReleaseMaterializer.MaterializedRelease release =
          services.buildApprovedRelease(vaultRoot, reviewRoot);
      List<dev.eugene.astroexport.release.ReferenceImpactIndex.InboundReference> inbound =
          release.audit().impactIndex().inboundTo(pageRef);
      LinkedHashMap<String, Integer> summary = new LinkedHashMap<>();
      summary.put("inboundLinksActivated", inbound.size());
      summary.put("affectedApprovedPages",
          (int) inbound.stream().map(
              dev.eugene.astroexport.release.ReferenceImpactIndex.InboundReference::pageRef)
              .distinct().count());
      summary.put("pendingDraftReferrers",
          (int) release.ignoredDrafts().stream()
              .filter(draft -> pageRef.equals(draft.targetRef()))
              .map(ApprovedReleaseMaterializer.IgnoredDraft::pageRef)
              .distinct().count());
      return summary;
    } catch (RuntimeException error) {
      return null;
    }
  }

  private CurrentPairState currentPairStateAfterConflict(
      Path vaultRoot,
      String note,
      Path source,
      Path reviewRoot,
      PublicationIdentity expectedIdentity) {
    try {
      StablePreflight current = stablePreflight(vaultRoot, note, source);
      if (!current.preflight().ready()
          || !expectedIdentity.samePublicIdentity(
              identityFromPreflight(current.preflight(), reviewRoot))) {
        return null;
      }
      ReviewPairState pair =
          reviewPairState(current.preflight().entry(), reviewRoot);
      return new CurrentPairState(pair.freshness(), pair.translationStatus());
    } catch (IOException
        | IllegalArgumentException
        | java.io.UncheckedIOException
        | PublicationDiscovery.PublicationSearchException error) {
      return null;
    }
  }

  private static String concurrentWorkflowRecovery(WorkflowStateService.ConcurrentFileUpdateException error) {
    ArrayList<String> details = new ArrayList<>();
    if (error.committed()) {
      details.add("The replacement may already be live because automatic rollback could not be confirmed.");
    }
    if (error.preservedPath() != null) {
      details.add("Recoverable bytes: " + error.preservedPath() + ".");
    }
    return details.isEmpty() ? "" : " " + String.join(" ", details);
  }

  private static void deleteTreeBestEffort(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // Temporary validation cleanup is best effort.
        }
      });
    } catch (IOException ignored) {
      // Temporary validation cleanup is best effort.
    }
  }

  public record PublicationIdentity(String collection, String publicId, Path reviewDirectory) {
    boolean samePublicIdentity(PublicationIdentity other) {
      return other != null && collection.equals(other.collection) && publicId.equals(other.publicId);
    }
  }

  private record Target(String collection, String publicId) { }

  private record ReviewPairState(String freshness, String translationStatus, String diagnostic, byte[] content) {
    private ReviewPairState {
      content = content == null ? null : content.clone();
    }

    @Override
    public byte[] content() {
      return content == null ? null : content.clone();
    }
  }

  private record StablePreflight(CommandServices.CliPreflight preflight, byte[] sourceSnapshot) {
    private StablePreflight {
      sourceSnapshot = sourceSnapshot.clone();
    }

    @Override
    public byte[] sourceSnapshot() {
      return sourceSnapshot.clone();
    }
  }

  private record CurrentPairState(String freshness, String translationStatus) { }

  private record ReleaseInspection(
      String approvedSnapshotState,
      String semanticReferencesState,
      String releaseState) { }

  private record DerivedRefreshState(
      String status,
      String translationStatus,
      String diagnostic,
      List<WorkflowStateService.SnapshotGuard> guards) {
    private DerivedRefreshState {
      guards = List.copyOf(guards);
    }
  }

  private static final class CliValidationException extends IllegalArgumentException {
    CliValidationException(String message) {
      super(message);
    }

    CliValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class LockBusyException extends Exception { }

  private record LockHandle(JnaFileDescriptor descriptor) implements AutoCloseable {
    @Override
    public void close() {
      try {
        descriptor.close();
      } catch (IOException ignored) {
        // Lock lifetime has ended.
      }
    }
  }

  @Command(name = "build-from-review")
  static final class BuildFromReviewCommand implements Callable<Integer> {
    @Spec CommandSpec spec;
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--vault", required = true) Path vault;
    @Option(names = "--out") Path out;
    @Option(names = "--dry-run") boolean dryRun;
    @Option(names = "--report") Path report = CommandServices.DEFAULT_REPORT_PATH;
    @Option(names = "--review") Path review = CommandServices.DEFAULT_REVIEW_ROOT;

    @Override
    public Integer call() {
      return parent.runExport(vault, out, dryRun, report, review, !dryRun);
    }
  }

  @Command(name = "migrate-overrides")
  static final class MigrateOverridesCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--overrides") Path overrides = CommandServices.DEFAULT_OVERRIDES_ROOT;
    @Option(names = "--review") Path review = CommandServices.DEFAULT_REVIEW_ROOT;

    @Override
    public Integer call() {
      parent.services.migrateOverrides(overrides, review).forEach(path -> parent.out().println(path));
      return 0;
    }
  }

  @Command(name = "prepare")
  static final class PrepareCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--vault", required = true) Path vault;
    @Option(names = "--note", required = true) String note;
    @Option(names = "--review") Path review = CommandServices.DEFAULT_REVIEW_ROOT;
    @Option(names = "--jobs") Path jobs = CommandServices.DEFAULT_JOBS_ROOT;
    @Option(names = "--json", required = true) boolean json;

    @Override
    public Integer call() {
      try {
        PrepareWorkflow.PrepareResult result = parent.services.prepare(vault, note, review, jobs);
        PublicationIdentity identity = result.entry() == null
            ? parent.identityFromPreflight(parent.services.preflight(vault, note), review)
            : parent.identityFromEntry(result.entry(), review);
        boolean ready = "ready_for_review".equals(result.status());
        boolean ok = ready && result.diagnostics().stream().noneMatch(PublicationDiagnostic::blocking);
        parent.emitJson(parent.bridge("prepare", ok, result.status())
            .note(note)
            .identity(identity)
            .diagnostics(result.diagnostics())
            .workspaceHealth(result.workspaceHealth())
            .jobId(result.jobId())
            .pairFreshness(ready ? "fresh" : null)
            .translationStatus(ready ? "generated" : null)
            .build());
        return ok ? 0 : 1;
      } catch (PublicationDiscovery.PublicationSearchException | java.io.UncheckedIOException error) {
        return parent.bridgeIoFailure("prepare", "translation_failed", note, error);
      }
    }
  }

  @Command(name = "inspect-publication")
  static final class InspectPublicationCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--vault", required = true) Path vault;
    @Option(names = "--note", required = true) String note;
    @Option(names = "--review") Path review = CommandServices.DEFAULT_REVIEW_ROOT;
    @Option(names = "--json", required = true) boolean json;

    @Override
    public Integer call() {
      try {
        return parent.inspect(vault, note, review);
      } catch (PublicationDiscovery.PublicationSearchException | java.io.UncheckedIOException error) {
        return parent.bridgeIoFailure("inspect-publication", "metadata_blocked", note, error);
      }
    }
  }

  @Command(name = "mark-reviewed")
  static final class MarkReviewedCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--vault", required = true) Path vault;
    @Option(names = "--note", required = true) String note;
    @Option(names = "--review") Path review = CommandServices.DEFAULT_REVIEW_ROOT;
    @Option(names = "--jobs") Path jobs = CommandServices.DEFAULT_JOBS_ROOT;
    @Option(names = "--json", required = true) boolean json;

    @Override
    public Integer call() {
      try {
        return parent.markReviewed(vault, note, review, jobs);
      } catch (PublicationDiscovery.PublicationSearchException | java.io.UncheckedIOException error) {
        return parent.bridgeIoFailure("mark-reviewed", "stale", note, error);
      }
    }
  }

  @Command(name = "migrate-semantic-links")
  static final class MigrateSemanticLinksCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--vault") Path vault;
    @Option(names = "--review", required = true) Path review;
    @Option(names = "--astro") Path astro;
    @Option(names = "--report") Path report;
    @Option(names = "--decisions") Path decisions;
    @Option(names = "--draft") Path draft;
    @Option(names = "--validate") boolean validate;
    @Option(names = "--apply") boolean apply;
    @Option(names = "--roll-forward") boolean rollForward;
    @Option(names = "--roll-back") boolean rollBack;
    @Option(names = "--json", required = true) boolean json;

    @Override
    public Integer call() {
      return parent.migrateSemanticLinks(
          vault,
          review,
          astro,
          report,
          decisions,
          apply,
          rollForward,
          rollBack,
          draft,
          validate);
    }
  }

  @Command(name = "refresh-publication-queue")
  static final class RefreshPublicationQueueCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--vault", required = true) Path vault;
    @Option(names = "--review") Path review = CommandServices.DEFAULT_REVIEW_ROOT;
    @Option(names = "--jobs") Path jobs = CommandServices.DEFAULT_JOBS_ROOT;
    @Option(names = "--json", required = true) boolean json;

    @Override
    public Integer call() {
      try {
        return parent.refresh(vault, review, jobs);
      } catch (PublicationDiscovery.PublicationSearchException | java.io.UncheckedIOException error) {
        return parent.refreshBridgeIoFailure(error);
      }
    }
  }

  @Command(name = "write-publication-contract")
  static final class WritePublicationContractCommand implements Callable<Integer> {
    @picocli.CommandLine.ParentCommand AstroExportCommand parent;
    @Option(names = "--out", required = true) Path out;

    @Override
    public Integer call() throws Exception {
      Path destination = out.toAbsolutePath().normalize();
      Path parentDirectory = destination.getParent();
      if (parentDirectory != null) {
        Files.createDirectories(parentDirectory);
      }
      Path temporary = Files.createTempFile(parentDirectory, "." + destination.getFileName(), ".tmp");
      Files.writeString(temporary, parent.services.renderPublicationContract(), StandardCharsets.UTF_8);
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      parent.out().println(out);
      return 0;
    }
  }
}
