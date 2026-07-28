package dev.eugene.astroexport.cli;

import dev.eugene.astroexport.discovery.PublicationDiscovery;
import dev.eugene.astroexport.fs.SiteWriter;
import dev.eugene.astroexport.manifest.ManifestBuilder;
import dev.eugene.astroexport.model.ManifestEntry;
import dev.eugene.astroexport.model.ManifestResult;
import dev.eugene.astroexport.model.Note;
import dev.eugene.astroexport.model.PublicationKind;
import dev.eugene.astroexport.model.SelectionResult;
import dev.eugene.astroexport.prepare.PrepareWorkflow;
import dev.eugene.astroexport.report.ReportBuilder;
import dev.eugene.astroexport.review.ReviewWorkspace;
import dev.eugene.astroexport.translation.TranslationValidator;
import dev.eugene.astroexport.validation.PreflightService;
import dev.eugene.astroexport.validation.PublicationDiagnostic;
import dev.eugene.astroexport.validation.PublicationValidator;
import dev.eugene.astroexport.workflow.WorkflowStateService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Runtime adapter bundle for CLI orchestration and focused tests. */
public final class CommandServices {
  public static final Path EXPORTER_ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  public static final Path DEFAULT_REPORT_PATH = EXPORTER_ROOT.resolve("report.md");
  public static final Path DEFAULT_REVIEW_ROOT = EXPORTER_ROOT.resolve("review");
  public static final Path DEFAULT_JOBS_ROOT = EXPORTER_ROOT.resolve(".publication-jobs");
  public static final Path DEFAULT_OVERRIDES_ROOT = EXPORTER_ROOT.resolve("overrides/en");

  private final Clock clock;
  private final SelectionAction selectionAction;
  private final ManifestAction manifestAction;
  private final EnglishManifestAction englishManifestAction;
  private final PrepareAction prepareAction;
  private final WriteSiteAction writeSiteAction;
  private final SiteWriter.GateRunner gateRunner;
  private final WorkflowStateService workflowState;
  private final PublicationValidator publicationValidator;
  private final PreflightService preflightService;
  private final PreflightObserver preflightObserver;
  private final MigrateOverridesAction migrateOverridesAction;
  private final WriteRuReviewAction writeRuReviewAction;
  private final ReplaceEnglishReviewAction replaceEnglishReviewAction;
  private final SnapshotPublishedAction snapshotPublishedAction;

  private CommandServices(
      Clock clock,
      SelectionAction selectionAction,
      ManifestAction manifestAction,
      EnglishManifestAction englishManifestAction,
      PrepareAction prepareAction,
      WriteSiteAction writeSiteAction,
      SiteWriter.GateRunner gateRunner,
      WorkflowStateService workflowState,
      PublicationValidator publicationValidator,
      PreflightService preflightService,
      PreflightObserver preflightObserver,
      MigrateOverridesAction migrateOverridesAction,
      WriteRuReviewAction writeRuReviewAction,
      ReplaceEnglishReviewAction replaceEnglishReviewAction,
      SnapshotPublishedAction snapshotPublishedAction) {
    this.clock = clock;
    this.selectionAction = selectionAction;
    this.manifestAction = manifestAction;
    this.englishManifestAction = englishManifestAction;
    this.prepareAction = prepareAction;
    this.writeSiteAction = writeSiteAction;
    this.gateRunner = gateRunner;
    this.workflowState = workflowState;
    this.publicationValidator = publicationValidator;
    this.preflightService = preflightService;
    this.preflightObserver = preflightObserver;
    this.migrateOverridesAction = migrateOverridesAction;
    this.writeRuReviewAction = writeRuReviewAction;
    this.replaceEnglishReviewAction = replaceEnglishReviewAction;
    this.snapshotPublishedAction = snapshotPublishedAction;
  }

  public static CommandServices defaults() {
    PublicationDiscovery discovery = new PublicationDiscovery();
    ManifestBuilder builder = new ManifestBuilder();
    return new CommandServices(
        Clock.systemUTC(),
        discovery::select,
        builder::buildRussianManifest,
        TranslationValidator::buildEnglishManifest,
        (vault, note, review, jobs, entryResolver) ->
            new PrepareWorkflow(entryResolver).prepare(vault, note, review, jobs),
        SiteWriter::writeSiteAtomic,
        CommandServices::runGate,
        new WorkflowStateService(),
        new PublicationValidator(),
        new PreflightService(),
        (vault, note) -> { },
        ReviewWorkspace::migrateOverrides,
        ReviewWorkspace::writeRuReviewFile,
        ReviewWorkspace::replaceEnglishReviewFile,
        ReviewWorkspace::snapshotPublished);
  }

  public CommandServices withClock(Clock replacement) {
    return copy(replacement, selectionAction, manifestAction, englishManifestAction, prepareAction,
        writeSiteAction, gateRunner);
  }

  public CommandServices withSelectionAction(SelectionAction replacement) {
    return copy(clock, replacement, manifestAction, englishManifestAction, prepareAction,
        writeSiteAction, gateRunner);
  }

  public CommandServices withPrepareAction(PrepareAction replacement) {
    return copy(clock, selectionAction, manifestAction, englishManifestAction, replacement,
        writeSiteAction, gateRunner);
  }

  public CommandServices withGateRunner(SiteWriter.GateRunner replacement) {
    return copy(clock, selectionAction, manifestAction, englishManifestAction, prepareAction,
        writeSiteAction, replacement);
  }

  public CommandServices withWriteSiteAction(WriteSiteAction replacement) {
    return copy(clock, selectionAction, manifestAction, englishManifestAction, prepareAction,
        replacement, gateRunner);
  }

  public CommandServices withPreflightObserver(PreflightObserver replacement) {
    return new CommandServices(
        clock,
        selectionAction,
        manifestAction,
        englishManifestAction,
        prepareAction,
        writeSiteAction,
        gateRunner,
        workflowState,
        publicationValidator,
        preflightService,
        replacement,
        migrateOverridesAction,
        writeRuReviewAction,
        replaceEnglishReviewAction,
        snapshotPublishedAction);
  }

  public CommandServices withReplaceEnglishReviewAction(ReplaceEnglishReviewAction replacement) {
    return new CommandServices(
        clock,
        selectionAction,
        manifestAction,
        englishManifestAction,
        prepareAction,
        writeSiteAction,
        gateRunner,
        workflowState,
        publicationValidator,
        preflightService,
        preflightObserver,
        migrateOverridesAction,
        writeRuReviewAction,
        replacement,
        snapshotPublishedAction);
  }

  public CommandServices withSnapshotPublishedAction(SnapshotPublishedAction replacement) {
    return new CommandServices(
        clock,
        selectionAction,
        manifestAction,
        englishManifestAction,
        prepareAction,
        writeSiteAction,
        gateRunner,
        workflowState,
        publicationValidator,
        preflightService,
        preflightObserver,
        migrateOverridesAction,
        writeRuReviewAction,
        replaceEnglishReviewAction,
        replacement);
  }

  private CommandServices copy(
      Clock newClock,
      SelectionAction newSelection,
      ManifestAction newManifest,
      EnglishManifestAction newEnglish,
      PrepareAction newPrepare,
      WriteSiteAction newWrite,
      SiteWriter.GateRunner newGate) {
    return new CommandServices(
        newClock,
        newSelection,
        newManifest,
        newEnglish,
        newPrepare,
        newWrite,
        newGate,
        workflowState,
        publicationValidator,
        preflightService,
        preflightObserver,
        migrateOverridesAction,
        writeRuReviewAction,
        replaceEnglishReviewAction,
        snapshotPublishedAction);
  }

  public Clock clock() {
    return clock;
  }

  public SelectionResult select(Path vault) {
    return selectionAction.select(vault);
  }

  public ManifestResult buildRussianManifest(SelectionResult selection) {
    return manifestAction.build(selection);
  }

  public ManifestResult buildEnglishManifest(ManifestResult russian, Path reviewRoot) {
    return englishManifestAction.build(russian, reviewRoot);
  }

  public PrepareWorkflow.PrepareResult prepare(Path vault, String note, Path review, Path jobs) {
    return prepareAction.prepare(vault, note, review, jobs, this::resolvePrepareEntry);
  }

  public SiteWriter.WriteResult writeSite(Path siteRoot, ManifestResult manifest, Consumer<Path> validator) {
    return writeSiteAction.write(siteRoot, manifest, validator);
  }

  public WorkflowStateService workflowState() {
    return workflowState;
  }

  public List<Path> migrateOverrides(Path overrides, Path review) {
    return migrateOverridesAction.migrate(overrides, review);
  }

  public Path writeRuReview(Path review, ManifestEntry entry) {
    return writeRuReviewAction.write(review, entry);
  }

  public Path replaceEnglishReview(Path review, String content, String collection, String publicId, byte[] expected)
      throws IOException {
    return replaceEnglishReview(review, content, collection, publicId, expected, List.of());
  }

  public Path replaceEnglishReview(
      Path review,
      String content,
      String collection,
      String publicId,
      byte[] expected,
      List<WorkflowStateService.SnapshotGuard> guards) throws IOException {
    return replaceEnglishReviewAction.replace(review, content, collection, publicId, expected, guards);
  }

  public void snapshotPublished(Path reviewRoot, ManifestResult manifest) {
    snapshotPublishedAction.snapshot(reviewRoot, manifest);
  }

  public Consumer<Path> astroGate(Path siteRoot) {
    Path workingDirectory = siteRoot.toAbsolutePath().normalize();
    return stagedRoot -> {
      LinkedHashMap<String, String> environment = new LinkedHashMap<>();
      environment.put("ASTRO_CONTENT_DIR", stagedRoot.resolve("src/content").toString());
      environment.put("ASTRO_PAGES_DIR", stagedRoot.resolve("src/data/pages").toString());
      environment.put("CI", "1");
      environment.put("NO_COLOR", "1");
      SiteWriter.GateInvocation invocation = new SiteWriter.GateInvocation(
          workingDirectory,
          List.of("npm", "run", "check"),
          environment);
      SiteWriter.GateResult result;
      try {
        result = gateRunner.run(invocation);
      } catch (IOException error) {
        throw new AstroGateException("could not start Astro content gate: " + error.getMessage(), error);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AstroGateException("Astro content gate interrupted", error);
      }
      if (result.exitCode() != 0) {
        StringBuilder details = new StringBuilder(
            "Astro content gate failed with exit code " + result.exitCode() + ".");
        if (!result.stdout().strip().isEmpty()) {
          details.append("\nstdout:\n").append(result.stdout().stripTrailing());
        }
        if (!result.stderr().strip().isEmpty()) {
          details.append("\nstderr:\n").append(result.stderr().stripTrailing());
        }
        throw new AstroGateException(details.toString());
      }
    };
  }

  public CliPreflight preflight(Path vault, String notePath) {
    try {
      preflightObserver.beforePreflight(vault, notePath);
    } catch (IOException error) {
      throw new java.io.UncheckedIOException(error);
    }
    PreflightService.Result loaded = preflightService.preflight(vault, notePath);
    if (!loaded.ready()) {
      return new CliPreflight(loaded.note(), null, loaded.diagnostics(), List.of());
    }
    SelectionResult selection = select(vault);
    List<PublicationDiagnostic> workspaceHealth = workspaceHealth(vault, notePath, selection);
    Optional<SelectionResult.Exclusion> activeExclusion = selection.excluded().stream()
        .filter(exclusion -> exclusion.vaultPath().equals(notePath))
        .findFirst();
    if (activeExclusion.isPresent()) {
      return new CliPreflight(
          loaded.note(),
          null,
          List.of(diagnosticFromExclusion(activeExclusion.get())),
          workspaceHealth);
    }
    boolean included = selection.included().stream().anyMatch(note -> note.vaultPath().equals(notePath));
    if (!included) {
      return new CliPreflight(
          loaded.note(),
          null,
          List.of(new PublicationDiagnostic("selection", notePath + ": must be selected for publication")),
          workspaceHealth);
    }
    ManifestResult manifest;
    try {
      manifest = buildRussianManifest(selection);
    } catch (ManifestBuilder.ManifestValidationException error) {
      if (error.sourcePath().equals(notePath)) {
        return new CliPreflight(
            loaded.note(),
            null,
            List.of(new PublicationDiagnostic(error.fieldName(), error.sourcePath() + ": " + error.reason())),
            workspaceHealth);
      }
      throw error;
    } catch (ManifestBuilder.ManifestTransclusionException error) {
      if (error.getMessage().startsWith(notePath + ":")) {
        return new CliPreflight(
            loaded.note(),
            null,
            List.of(new PublicationDiagnostic("transclusion", error.getMessage())),
            workspaceHealth);
      }
      throw error;
    }
    ManifestEntry entry = manifest.entries().stream()
        .filter(item -> item.sourcePath().equals(notePath))
        .findFirst()
        .orElse(null);
    return new CliPreflight(loaded.note(), entry, List.of(), workspaceHealth);
  }

  private ManifestEntry resolvePrepareEntry(Path vault, String notePath) {
    CliPreflight resolved = preflight(vault, notePath);
    if (resolved.ready()) {
      return resolved.entry();
    }
    String diagnostic = resolved.diagnostics().stream()
        .map(item -> item.field() + ": " + item.message())
        .reduce((first, second) -> first + "; " + second)
        .orElse(notePath + ": must be selected for publication");
    throw new IllegalArgumentException(diagnostic);
  }

  private List<PublicationDiagnostic> workspaceHealth(Path vault, String currentPath, SelectionResult selection) {
    return selection.excluded().stream()
        .filter(exclusion -> !exclusion.vaultPath().equals(currentPath))
        .map(exclusion -> {
          PreflightService.Result result = preflightService.preflight(vault, exclusion.vaultPath());
          if (!result.diagnostics().isEmpty()) {
            return result.diagnostics().getFirst();
          }
          return diagnosticFromExclusion(exclusion);
        })
        .toList();
  }

  private PublicationDiagnostic diagnosticFromExclusion(SelectionResult.Exclusion exclusion) {
    if (exclusion.field() != null) {
      return new PublicationDiagnostic(exclusion.field(), exclusion.vaultPath() + ": " + exclusion.reason());
    }
    String reason = exclusion.reason();
    String field;
    if (reason.startsWith("missing publicId")
        || reason.startsWith("invalid publicId")
        || reason.startsWith("duplicate publicId")) {
      field = "publicId";
    } else if (reason.startsWith("missing publicCollection")
        || reason.startsWith("unsupported publicCollection")) {
      field = "publicCollection";
    } else if (reason.startsWith("missing publicContentType")
        || reason.startsWith("publicContentType")) {
      field = "publicContentType";
    } else if (reason.startsWith("invalid frontmatter")) {
      field = "frontmatter";
    } else {
      field = "selection";
    }
    return new PublicationDiagnostic(field, exclusion.vaultPath() + ": " + reason);
  }

  public String renderPublicationContract() {
    return PublicationContractRenderer.render();
  }

  private static SiteWriter.GateResult runGate(SiteWriter.GateInvocation invocation)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(invocation.command());
    builder.directory(invocation.workingDirectory().toFile());
    builder.environment().putAll(invocation.environment());
    Process process = builder.start();
    CompletableFuture<String> stdout = read(process.getInputStream());
    CompletableFuture<String> stderr = read(process.getErrorStream());
    int exitCode = process.waitFor();
    return new SiteWriter.GateResult(exitCode, stdout.join(), stderr.join());
  }

  private static CompletableFuture<String> read(InputStream stream) {
    return CompletableFuture.supplyAsync(() -> {
      try (stream) {
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException error) {
        throw new java.io.UncheckedIOException(error);
      }
    });
  }

  @FunctionalInterface
  public interface PreflightObserver {
    void beforePreflight(Path vault, String notePath) throws IOException;
  }

  @FunctionalInterface
  public interface SelectionAction {
    SelectionResult select(Path vault);
  }

  @FunctionalInterface
  public interface ManifestAction {
    ManifestResult build(SelectionResult selection);
  }

  @FunctionalInterface
  public interface EnglishManifestAction {
    ManifestResult build(ManifestResult russian, Path reviewRoot);
  }

  @FunctionalInterface
  public interface PrepareAction {
    PrepareWorkflow.PrepareResult prepare(
        Path vault,
        String note,
        Path review,
        Path jobs,
        PrepareWorkflow.EntryResolver entryResolver);
  }

  @FunctionalInterface
  public interface WriteSiteAction {
    SiteWriter.WriteResult write(Path siteRoot, ManifestResult manifest, Consumer<Path> validator);
  }

  @FunctionalInterface
  public interface MigrateOverridesAction {
    List<Path> migrate(Path overrides, Path review);
  }

  @FunctionalInterface
  public interface WriteRuReviewAction {
    Path write(Path review, ManifestEntry entry);
  }

  @FunctionalInterface
  public interface ReplaceEnglishReviewAction {
    Path replace(
        Path review,
        String content,
        String collection,
        String publicId,
        byte[] expected,
        List<WorkflowStateService.SnapshotGuard> guards) throws IOException;
  }

  @FunctionalInterface
  public interface SnapshotPublishedAction {
    void snapshot(Path reviewRoot, ManifestResult manifest);
  }

  public record CliPreflight(
      Note note,
      ManifestEntry entry,
      List<PublicationDiagnostic> diagnostics,
      List<PublicationDiagnostic> workspaceHealth) {
    public CliPreflight {
      diagnostics = List.copyOf(diagnostics);
      workspaceHealth = List.copyOf(workspaceHealth);
    }

    public boolean ready() {
      return entry != null && diagnostics.stream().noneMatch(PublicationDiagnostic::blocking);
    }
  }

  public static final class AstroGateException extends RuntimeException {
    public AstroGateException(String message) {
      super(message);
    }

    public AstroGateException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final class PublicationContractRenderer {
    private static final Map<String, String> WORKFLOW_STATUS_DESCRIPTIONS = new LinkedHashMap<>();
    private static final List<Map.Entry<String, String>> AUTHOR_ACTIONS = List.of(
        Map.entry("Prepare current note for public site", "проверяет текущую заметку и готовит валидный английский черновик. Команда не меняет `publish` и не запускает сборку Astro."),
        Map.entry("Open current translation review", "открывает внешний каталог с `ru.md` и `en.md` для ручной проверки."),
        Map.entry("Mark current translation reviewed", "после проверки валидирует `en.md`, отмечает перевод как `reviewed` и обновляет состояние заметки."),
        Map.entry("Refresh publication queue", "сверяет опубликованные исходные заметки с внешними переводами и обновляет живую очередь."));

    static {
      WORKFLOW_STATUS_DESCRIPTIONS.put("metadata_blocked", "Не хватает обязательных метаданных или они неверны; исправьте список требований в исходной заметке.");
      WORKFLOW_STATUS_DESCRIPTIONS.put("translating", "Подготовка английского черновика выполняется.");
      WORKFLOW_STATUS_DESCRIPTIONS.put("ready_for_review", "Английский файл валиден и готов к ручной проверке.");
      WORKFLOW_STATUS_DESCRIPTIONS.put("ready_to_publish", "Английский текст вручную проверен и готов к ручному выпуску сайта.");
      WORKFLOW_STATUS_DESCRIPTIONS.put("translation_failed", "Подготовка не завершилась; последний валидный английский файл, если он был, сохранён.");
      WORKFLOW_STATUS_DESCRIPTIONS.put("stale", "Публичное русское содержание изменилось; запустите Prepare снова.");
    }

    private PublicationContractRenderer() { }

    static String render() {
      java.util.ArrayList<String> lines = new java.util.ArrayList<>(List.of(
          "---",
          "title: Подготовка заметок к публикации на Astro-сайте",
          "type: sop",
          "status: active",
          "private: true",
          "created: 2026-07-18",
          "---",
          "",
          "# Подготовка заметок к публикации на Astro-сайте",
          "",
          "> [!note] Сгенерированный договор",
          "> Этот файл создаётся exporter-ом из единой схемы публикации. Требования к полям не следует редактировать вручную.",
          "",
          "## Намерение публиковать",
          "",
          "`publish: true` автор устанавливает вручную в исходной заметке. Команды подготовки не добавляют, не удаляют и не изменяют это поле.",
          "",
          "После этого выберите поддерживаемую пару `publicCollection/publicContentType` и заполните требования соответствующего раздела.",
          "",
          "## Поддерживаемые пары и поля",
          ""));
      for (PublicationKind kind : PublicationKind.all()) {
        lines.add("### " + kind.collection() + "/" + kind.contentType());
        lines.add("");
        kind.requirements().forEach(requirement -> {
          String fields = requirement.fields().stream()
              .map(field -> "`" + field + "`")
              .collect(java.util.stream.Collectors.joining(" / "));
          String alternative = requirement.fields().size() > 1 ? "одно из полей " : "";
          String source = requirement.source().equals("frontmatter") ? "frontmatter" : "текст заметки";
          lines.add("- " + source + ": " + alternative + fields + " — "
              + requirement.authorExpectation() + ".");
        });
        lines.add("");
      }
      lines.addAll(List.of(
          "## Где находится перевод",
          "",
          "Русская заметка остаётся источником в vault. Русская публичная проекция и английский текст лежат вне vault в `tools/astro-export/review/<collection>/<publicId>/` как `ru.md` и `en.md`. Поэтому английские черновики не попадают в поиск и варианты ссылок Obsidian.",
          "",
          "## Команды в Obsidian",
          ""));
      for (int index = 0; index < AUTHOR_ACTIONS.size(); index++) {
        Map.Entry<String, String> action = AUTHOR_ACTIONS.get(index);
        lines.add((index + 1) + ". **" + action.getKey() + "** — " + action.getValue());
      }
      lines.addAll(List.of(
          "",
          "## Состояния",
          "",
          "| `publicWorkflowStatus` | Значение |",
          "| --- | --- |"));
      WORKFLOW_STATUS_DESCRIPTIONS.forEach((status, description) ->
          lines.add("| `" + status + "` | " + description + " |"));
      lines.addAll(List.of(
          "",
          "`publicTranslationStatus` принимает `generated` или `reviewed`. Оба значения допустимы для сборки; `reviewed` означает явную ручную проверку.",
          "",
          "## Ручной выпуск сайта",
          "",
          "Подготовка и проверка перевода не публикуют сайт. Сборка из валидных файлов проверки, проверка Astro и развёртывание — отдельный ручной этап оператора. Prepare не запускает сборку Astro, preview, npm или развёртывание.",
          "",
          "Оператор использует `astro-export build-from-review` и затем обычную команду сборки сайта; точные команды описаны в `tools/astro-export/README.md`.",
          ""));
      return String.join("\n", lines).stripTrailing() + "\n";
    }
  }
}
