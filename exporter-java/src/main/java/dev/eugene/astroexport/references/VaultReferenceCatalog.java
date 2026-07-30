package dev.eugene.astroexport.references;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.fs.AtomicExchange;
import dev.eugene.astroexport.fs.JnaAtomicExchange;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Persistent catalog for stable vault identifiers used by late-bound semantic links.
 */
public record VaultReferenceCatalog(int schemaVersion, Map<String, CatalogEntry> entries) {

  public static final int SCHEMA_VERSION = 1;
  public static final String STATE_ACTIVE = "active";
  public static final String STATE_TOMBSTONE = "tombstone";
  private static final String CATALOG_DIR = ".semantic-links";
  private static final String CATALOG_FILE = "catalog-v1.json";
  private static final Pattern PAGE_REF = Pattern.compile("^vault-ref-(\\d+)$");
  private static final ObjectMapper JSON = new ObjectMapper(new JsonFactory()
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
  private static final AtomicExchange ATOMIC_EXCHANGE = new JnaAtomicExchange();

  public VaultReferenceCatalog {
    entries = Map.copyOf(entries);
  }

  public static VaultReferenceCatalog empty() {
    return new VaultReferenceCatalog(SCHEMA_VERSION, Map.of());
  }

  public static Path catalogPath(Path reviewRoot) {
    return reviewRoot.resolve(CATALOG_DIR).resolve(CATALOG_FILE);
  }

  public static VaultReferenceCatalog load(Path reviewRoot) {
    Path path = catalogPath(reviewRoot);
    if (!Files.exists(path)) {
      return empty();
    }
    try {
      return read(Files.readAllBytes(path));
    } catch (IOException error) {
      throw new UncheckedIOException("cannot read catalog: " + path, error);
    }
  }

  public static VaultReferenceCatalog read(byte[] content) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> payload = JSON.readValue(content, Map.class);
      int schemaVersion = requiredInt(payload.get("schemaVersion"), "schemaVersion");
      if (schemaVersion != SCHEMA_VERSION) {
        throw new CatalogValidationException("invalid-schema-version", "schemaVersion must be " + SCHEMA_VERSION);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> rawEntries = requiredMap(payload.get("entries"), "entries");
      Map<String, CatalogEntry> entries = new LinkedHashMap<>();
      for (Map.Entry<String, Object> entry : rawEntries.entrySet()) {
        if (!(entry.getValue() instanceof Map<?, ?> raw)) {
          throw new CatalogValidationException("invalid-entry", "catalog entry must be an object");
        }
        entries.put(entry.getKey(), CatalogEntry.parse(entry.getKey(), raw));
      }
      return new VaultReferenceCatalog(schemaVersion, entries);
    } catch (com.fasterxml.jackson.core.exc.StreamReadException duplicateKey) {
      throw new CatalogValidationException("duplicate-key", duplicateKey.getMessage());
    } catch (CatalogValidationException error) {
      throw error;
    } catch (Exception error) {
      throw new CatalogValidationException("invalid-json", error.getMessage());
    }
  }

  public byte[] write() {
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("schemaVersion", SCHEMA_VERSION);
      LinkedHashMap<String, Object> serializedEntries = new LinkedHashMap<>();
      for (String pageRef : entries.keySet().stream().sorted().toList()) {
        serializedEntries.put(pageRef, entries.get(pageRef).write());
      }
      payload.put("entries", serializedEntries);
      return JSON.writeValueAsBytes(payload);
    } catch (Exception error) {
      throw new IllegalStateException("cannot serialize catalog", error);
    }
  }

  public void writeAtomically(Path reviewRoot) {
    Path path = catalogPath(reviewRoot);
    try {
      Files.createDirectories(path.getParent());
      if (!Files.exists(path)) {
        Files.writeString(path, "{}");
      }
      byte[] payload = write();
      Path staging = Files.createTempFile(path.getParent(), ".catalog-v1", ".tmp");
      try {
        writeDurably(staging, payload);
        ATOMIC_EXCHANGE.exchange(path, staging);
        forceDirectory(path.getParent());
      } finally {
        Files.deleteIfExists(staging);
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot write catalog atomically: " + path, error);
    }
  }

  public VaultReferenceCatalog reconcile(Path vaultRoot, List<VaultNoteDescriptor> descriptors) {
    Map<String, CatalogEntry> active = entries;
    Set<String> preserved = new LinkedHashSet<>();
    Map<String, CatalogEntry> nextEntries = new LinkedHashMap<>();

    for (VaultNoteDescriptor descriptor : descriptors) {
      String pageRef = resolveMatch(active, descriptor);
      CatalogEntry previous = pageRef == null ? null : active.get(pageRef);
      if (previous != null) {
        List<String> previousPaths = new ArrayList<>(previous.previousPaths());
        if (!previous.currentPath().equals(descriptor.vaultPath())) {
          previousPaths.add(previous.currentPath());
          previousPaths = previousPaths.stream().distinct().toList();
        }
        CatalogEntry reconciled = new CatalogEntry(
            pageRef,
            descriptor.vaultPath(),
            descriptor.stableNoteId(),
            descriptor.title(),
            descriptor.aliases(),
            previousPaths,
            STATE_ACTIVE);
        nextEntries.put(pageRef, reconciled);
      } else {
        String nextRef = allocatePageRef(active.keySet());
        nextEntries.put(nextRef, new CatalogEntry(
            nextRef,
            descriptor.vaultPath(),
            descriptor.stableNoteId(),
            descriptor.title(),
            descriptor.aliases(),
            List.of(),
            STATE_ACTIVE));
      }
      if (pageRef != null) {
        preserved.add(pageRef);
      }
    }

    for (Map.Entry<String, CatalogEntry> entry : active.entrySet()) {
      if (preserved.contains(entry.getKey())) {
        continue;
      }
      if (STATE_ACTIVE.equals(entry.getValue().state())) {
        nextEntries.put(entry.getKey(), entry.getValue().withState(STATE_TOMBSTONE));
      } else {
        nextEntries.put(entry.getKey(), entry.getValue());
      }
    }

    return new VaultReferenceCatalog(SCHEMA_VERSION, nextEntries);
  }

  public CatalogEntry requireByCurrentPath(String vaultPath) {
    return requireByCurrentPathOptional(vaultPath)
        .orElseThrow(() -> new IllegalArgumentException("no catalog entry for current path: " + vaultPath));
  }

  public Optional<CatalogEntry> requireByCurrentPathOptional(String vaultPath) {
    return entries.values().stream()
        .filter(entry -> STATE_ACTIVE.equals(entry.state()) && entry.currentPath().equals(vaultPath))
        .findFirst();
  }

  private static String resolveMatch(Map<String, CatalogEntry> entries, VaultNoteDescriptor descriptor) {
    String match = unique(entries.entrySet().stream()
        .filter(entry -> STATE_ACTIVE.equals(entry.getValue().state())
            && entry.getValue().currentPath().equals(descriptor.vaultPath()))
        .map(Map.Entry::getKey)
        .toList());
    if (match != null) {
      return match;
    }

    if (descriptor.stableNoteId() != null && !descriptor.stableNoteId().isBlank()) {
      match = unique(entries.entrySet().stream()
          .filter(entry -> STATE_ACTIVE.equals(entry.getValue().state())
              && descriptor.stableNoteId().equals(entry.getValue().stableNoteId()))
          .map(Map.Entry::getKey)
          .toList());
      if (match != null) {
        return match;
      }
    }

    match = unique(entries.entrySet().stream()
        .filter(entry -> STATE_ACTIVE.equals(entry.getValue().state())
            && entry.getValue().previousPaths().contains(descriptor.vaultPath()))
        .map(Map.Entry::getKey)
        .toList());
    if (match != null) {
      return match;
    }

    match = unique(entries.entrySet().stream()
        .filter(entry -> STATE_ACTIVE.equals(entry.getValue().state())
            && !descriptor.aliases().isEmpty()
            && descriptor.aliases().stream().anyMatch(entry.getValue().aliases()::contains))
        .map(Map.Entry::getKey)
        .toList());
    return match;
  }

  private static String unique(List<String> values) {
    return values.size() == 1 ? values.getFirst() : null;
  }

  private static String allocatePageRef(Collection<String> entries) {
    int max = 0;
    for (String pageRef : entries) {
      Matcher matcher = PAGE_REF.matcher(pageRef);
      if (!matcher.matches()) {
        continue;
      }
      try {
        max = Math.max(max, Integer.parseInt(matcher.group(1)));
      } catch (NumberFormatException ignored) {
        // Ignore malformed refs.
      }
    }
    return "vault-ref-%04d".formatted(max + 1);
  }

  private static void writeDurably(Path path, byte[] payload) throws IOException {
    try (FileChannel channel = FileChannel.open(
        path,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING)) {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
    forceDirectory(path);
  }

  private static void forceDirectory(Path path) {
    try {
      if (Files.isDirectory(path)) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
          channel.force(true);
        }
      }
    } catch (IOException | UnsupportedOperationException ignored) {
      // Best effort.
    }
  }

  private static int requiredInt(Object value, String field) {
    if (value instanceof Integer number) {
      return number;
    }
    throw new CatalogValidationException("invalid-json", field + " must be an integer");
  }

  private static Map<String, Object> requiredMap(Object value, String field) {
    if (!(value instanceof Map<?, ?> values)) {
      throw new CatalogValidationException("invalid-json", field + " must be an object");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) values;
    return result;
  }

  private static String requiredString(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new CatalogValidationException("invalid-json", field + " must be a non-blank string");
    }
    return text;
  }

  public record CatalogEntry(
      String pageRef,
      String currentPath,
      String stableNoteId,
      String title,
      List<String> aliases,
      List<String> previousPaths,
      String state) {

    public CatalogEntry {
      aliases = List.copyOf(aliases);
      previousPaths = List.copyOf(previousPaths);
    }

    public CatalogEntry withState(String nextState) {
      return new CatalogEntry(pageRef, currentPath, stableNoteId, title, aliases, previousPaths, nextState);
    }

    private Map<String, Object> write() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("currentPath", currentPath);
      if (stableNoteId != null && !stableNoteId.isBlank()) {
        payload.put("stableNoteId", stableNoteId);
      }
      if (title != null && !title.isBlank()) {
        payload.put("title", title);
      }
      payload.put("aliases", aliases);
      payload.put("previousPaths", previousPaths);
      payload.put("state", state);
      return payload;
    }

    private static CatalogEntry parse(String pageRef, Map<?, ?> payload) {
      String currentPath = requiredString(payload.get("currentPath"), "currentPath");
      String stableNoteId = payload.get("stableNoteId") instanceof String id && !id.isBlank() ? id : null;
      String title = payload.get("title") instanceof String text ? text : "";
      List<String> aliases = strings(payload.get("aliases"));
      List<String> previousPaths = strings(payload.get("previousPaths"));
      String state = requiredState(payload.get("state"));
      return new CatalogEntry(pageRef, currentPath, stableNoteId, title, aliases, previousPaths, state);
    }

    private static List<String> strings(Object value) {
      if (!(value instanceof List<?> values)) {
        return List.of();
      }
      Set<String> result = new LinkedHashSet<>();
      for (Object item : values) {
        if (!(item instanceof String text)) {
          continue;
        }
        String normalized = text.strip();
        if (!normalized.isBlank()) {
          result.add(normalized);
        }
      }
      return List.copyOf(result);
    }
  }

  private static String requiredState(Object value) {
    if (!(value instanceof String state)) {
      return STATE_ACTIVE;
    }
    if (!STATE_ACTIVE.equals(state) && !STATE_TOMBSTONE.equals(state)) {
      throw new CatalogValidationException("invalid-json", "state must be active or tombstone");
    }
    return state;
  }

  public static final class CatalogValidationException extends RuntimeException {
    private final String code;

    public CatalogValidationException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
