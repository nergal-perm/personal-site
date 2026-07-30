package dev.eugene.astroexport.references;

import dev.eugene.astroexport.frontmatter.FrontmatterDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Snapshot of one vault note used to build a stable semantic reference catalog.
 */
public record VaultNoteDescriptor(
    String vaultPath,
    String filenameStem,
    String stableNoteId,
    String title,
    List<String> aliases,
    List<String> diagnostics) {

  public VaultNoteDescriptor {
    aliases = List.copyOf(aliases);
    diagnostics = List.copyOf(diagnostics);
  }

  public static List<VaultNoteDescriptor> scan(Path vaultRoot) {
    Map<String, Integer> stableIdCounts = new LinkedHashMap<>();
    List<VaultNoteDescriptor> descriptors = new ArrayList<>();

    try (Stream<Path> walk = Files.walk(vaultRoot)) {
      for (Path path : walk.toList()) {
        if (!Files.isRegularFile(path)
            || Files.isSymbolicLink(path)
            || !path.getFileName().toString().endsWith(".md")) {
          continue;
        }
        VaultNoteDescriptor descriptor = readNote(vaultRoot, path);
        descriptors.add(descriptor);
        if (descriptor.stableNoteId() != null) {
          stableIdCounts.merge(descriptor.stableNoteId(), 1, Integer::sum);
        }
      }
    } catch (IOException error) {
      throw new UncheckedIOException("cannot scan vault: " + vaultRoot, error);
    }

    return descriptors.stream()
        .map(descriptor -> sanitizeIdentity(descriptor, stableIdCounts))
        .sorted(Comparator.comparing(VaultNoteDescriptor::vaultPath))
        .toList();
  }

  private static VaultNoteDescriptor readNote(Path vaultRoot, Path path) {
    String vaultPath = vaultRoot.relativize(path).toString().replace('\\', '/');
    List<String> diagnostics = new ArrayList<>();

    if (isUnsafeVaultPath(vaultPath)) {
      diagnostics.add("unsafe-vault-path: " + vaultPath);
    }

    String fileName = path.getFileName().toString();
    String filenameStem = fileName.endsWith(".md")
        ? fileName.substring(0, fileName.length() - 3)
        : fileName;

    String content;
    try {
      content = Files.readString(path, StandardCharsets.UTF_8);
    } catch (MalformedInputException error) {
      diagnostics.add("invalid-utf-8: " + vaultPath);
      return new VaultNoteDescriptor(vaultPath, filenameStem, null, null, List.of(), diagnostics);
    } catch (IOException error) {
      diagnostics.add("unreadable-note: " + vaultPath);
      return new VaultNoteDescriptor(vaultPath, filenameStem, null, null, List.of(), diagnostics);
    }

    String stableNoteId = null;
    String title = null;
    List<String> aliases = List.of();

    try {
      FrontmatterDocument frontmatter = FrontmatterDocument.parse(path, vaultPath, content);
      stableNoteId = extractString(frontmatter.metadata().get("id"));
      title = extractString(frontmatter.metadata().get("title"));
      aliases = extractAliases(frontmatter.metadata().get("aliases"));
      if (stableNoteId != null && (stableNoteId.contains("/") || stableNoteId.contains("\\"))) {
        diagnostics.add("invalid-stable-note-id: " + stableNoteId);
        stableNoteId = null;
      }
    } catch (RuntimeException error) {
      diagnostics.add("invalid-frontmatter: " + vaultPath);
    }

    return new VaultNoteDescriptor(vaultPath, filenameStem, stableNoteId, title, aliases, diagnostics);
  }

  private static VaultNoteDescriptor sanitizeIdentity(VaultNoteDescriptor descriptor, Map<String, Integer> stableIdCounts) {
    if (descriptor.stableNoteId() != null && stableIdCounts.getOrDefault(descriptor.stableNoteId(), 0) > 1) {
      List<String> diagnostics = new ArrayList<>(descriptor.diagnostics());
      diagnostics.add("copied-identity: " + descriptor.stableNoteId());
      diagnostics.add("duplicate-stable-id: " + descriptor.stableNoteId());
      return new VaultNoteDescriptor(
          descriptor.vaultPath(),
          descriptor.filenameStem(),
          null,
          descriptor.title(),
          descriptor.aliases(),
          diagnostics);
    }
    return descriptor;
  }

  private static String extractString(Object value) {
    if (!(value instanceof String raw) || raw.isBlank()) {
      return null;
    }
    String normalized = raw.strip();
    return normalized.isBlank() ? null : normalized;
  }

  private static List<String> extractAliases(Object value) {
    if (value == null) {
      return List.of();
    }
    if (value instanceof String alias) {
      String normalized = alias.strip();
      return normalized.isBlank() ? List.of() : List.of(normalized);
    }
    if (!(value instanceof List<?> values)) {
      return List.of();
    }

    LinkedHashSet<String> aliases = new LinkedHashSet<>();
    for (Object item : values) {
      if (!(item instanceof String alias)) {
        continue;
      }
      String normalized = alias.strip();
      if (!normalized.isBlank()) {
        aliases.add(normalized);
      }
    }
    return List.copyOf(aliases);
  }

  private static boolean isUnsafeVaultPath(String vaultPath) {
    if (vaultPath.isBlank() || vaultPath.startsWith("/") || vaultPath.startsWith(".\\")) {
      return true;
    }
    return !Path.of(vaultPath).normalize().toString().replace('\\', '/').equals(vaultPath);
  }
}
