package dev.eugene.astroexport.references;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Resolves authored Obsidian targets into stable catalog references. */
public final class VaultReferenceResolver {
  private static final Pattern TIMESTAMP = Pattern.compile("^\\d{12}\\s+");
  private final VaultReferenceCatalog catalog;

  public VaultReferenceResolver(VaultReferenceCatalog catalog) {
    this.catalog = catalog;
  }

  public Resolution resolve(String sourcePath, String authoredTarget) {
    ParsedTarget parsed = ParsedTarget.parse(authoredTarget);
    return resolveTarget(parsed.target(), parsed.heading());
  }

  public Resolution resolveTarget(String authoredTarget) {
    ParsedTarget parsed = ParsedTarget.parse(authoredTarget);
    return resolveTarget(parsed.target(), parsed.heading());
  }

  private Resolution resolveTarget(String target, String heading) {
    String normalizedTarget = normalizeTarget(target);

    for (Layer layer : new Layer[] {
        this::pathLayer,
        this::stableIdLayer,
        this::stemLayer,
        this::titleLayer,
        this::aliasLayer}) {
      Resolution resolution = resolveLayer(normalizedTarget, layer);
      if (resolution.status() != Status.UNRESOLVED) {
        return new Resolution(resolution.status(), resolution.pageRef(), resolution.currentPath(), heading);
      }
    }

    return new Resolution(Status.UNRESOLVED, null, null, heading);
  }

  private Resolution resolveLayer(String normalizedTarget, Layer layer) {
    List<String> matches = new ArrayList<>();
    for (VaultReferenceCatalog.CatalogEntry entry : catalog.entries().values()) {
      if (!VaultReferenceCatalog.STATE_ACTIVE.equals(entry.state())) {
        continue;
      }
      if (layer.matches(entry, normalizedTarget)) {
        matches.add(entry.pageRef());
      }
    }
    if (matches.size() == 1) {
      VaultReferenceCatalog.CatalogEntry entry = catalog.entries().get(matches.getFirst());
      return new Resolution(Status.RESOLVED, entry.pageRef(), entry.currentPath(), null);
    }
    if (matches.size() > 1) {
      return new Resolution(Status.AMBIGUOUS, null, null, null);
    }
    return new Resolution(Status.UNRESOLVED, null, null, null);
  }

  private boolean pathLayer(VaultReferenceCatalog.CatalogEntry entry, String target) {
    String normalizedTarget = stripExtension(normalizeVaultPath(target));
    String normalizedCurrent = stripExtension(normalizeVaultPath(entry.currentPath()));
    return normalizedTarget.equals(normalizedCurrent);
  }

  private boolean stableIdLayer(VaultReferenceCatalog.CatalogEntry entry, String target) {
    return entry.stableNoteId() != null && entry.stableNoteId().equals(target);
  }

  private boolean stemLayer(VaultReferenceCatalog.CatalogEntry entry, String target) {
    String targetStem = stripExtension(timestampStripper(stem(normalizeVaultPath(target))));
    String entryStem = stripExtension(timestampStripper(stem(normalizeVaultPath(entry.currentPath()))));
    return !targetStem.isBlank() && targetStem.equals(entryStem);
  }

  private boolean titleLayer(VaultReferenceCatalog.CatalogEntry entry, String target) {
    return entry.title() != null && !entry.title().isBlank() && entry.title().equals(target);
  }

  private boolean aliasLayer(VaultReferenceCatalog.CatalogEntry entry, String target) {
    return entry.aliases().contains(target);
  }

  private static String normalizeTarget(String target) {
    if (target == null) {
      return "";
    }
    return target.strip();
  }

  private static String normalizeVaultPath(String value) {
    return value == null ? "" : value.replace('\\', '/');
  }

  private static String stripExtension(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.endsWith(".md") ? value.substring(0, value.length() - 3) : value;
  }

  private static String stem(String value) {
    try {
      return Path.of(value).getFileName().toString();
    } catch (RuntimeException error) {
      return value;
    }
  }

  private static String timestampStripper(String value) {
    return TIMESTAMP.matcher(value).replaceFirst("");
  }

  public record Resolution(Status status, String pageRef, String currentPath, String heading) { }

  public enum Status {
    RESOLVED,
    AMBIGUOUS,
    UNRESOLVED
  }

  private interface Layer {
    boolean matches(VaultReferenceCatalog.CatalogEntry entry, String target);
  }

  private static record ParsedTarget(String target, String heading) {
    static ParsedTarget parse(String value) {
      if (value == null || value.isBlank()) {
        return new ParsedTarget("", "");
      }
      int hash = value.indexOf('#');
      if (hash < 0) {
        return new ParsedTarget(value, "");
      }
      return new ParsedTarget(value.substring(0, hash), value.substring(hash));
    }
  }
}
