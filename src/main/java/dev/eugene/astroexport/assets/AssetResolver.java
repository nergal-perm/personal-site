package dev.eugene.astroexport.assets;

import dev.eugene.astroexport.markdown.MarkdownScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves visible vault assets and rewrites their rendered Obsidian embeds. */
public final class AssetResolver {
  private static final Set<String> ASSET_EXTENSIONS = Set.of(
      ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".mp3", ".mp4");
  private static final Pattern WIKILINK = Pattern.compile("(!?)\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]");
  private final AssetFileReader fileReader;

  public AssetResolver() { this(Files::readAllBytes); }

  AssetResolver(AssetFileReader fileReader) { this.fileReader = fileReader; }

  public List<ResolvedAsset> resolveAssets(Path vault, List<String> references) {
    List<String> orderedReferences = references.stream().distinct().sorted().toList();
    Path vaultRoot;
    try {
      vaultRoot = vault.toRealPath();
    } catch (IOException exception) {
      String reference = orderedReferences.isEmpty() ? "<vault>" : orderedReferences.getFirst();
      throw invalid(reference, "cannot resolve vault", exception);
    }

    List<ResolvedAsset> resolved = new ArrayList<>();
    Map<Path, byte[]> contentBySource = new HashMap<>();
    Map<String, String> suffixByDigest = new HashMap<>();
    for (String reference : orderedReferences) {
      Path sourcePath = resolveSource(vault, vaultRoot, reference);
      byte[] content = contentBySource.get(sourcePath);
      if (content == null) {
        try {
          content = fileReader.read(sourcePath);
        } catch (IOException exception) {
          throw invalid(reference, "cannot read file", exception);
        }
        contentBySource.put(sourcePath, content);
      }
      String digest = sha256(content);
      String suffix = canonicalSuffix(sourcePath);
      String existingSuffix = suffixByDigest.putIfAbsent(digest, suffix);
      if (existingSuffix != null && !existingSuffix.equals(suffix)) {
        throw new AssetValidationException(reference,
            "byte-identical assets have incompatible suffix families: " + existingSuffix + " and " + suffix);
      }
      String outputName = digest + suffix;
      resolved.add(new ResolvedAsset(reference, sourcePath, outputName, "/assets/vault/" + outputName, digest));
    }
    return List.copyOf(resolved);
  }

  public String rewriteAssetEmbeds(String body, Collection<ResolvedAsset> allowlist) {
    Map<String, ResolvedAsset> assets = new HashMap<>();
    for (ResolvedAsset asset : allowlist) assets.put(asset.reference(), asset);
    StringBuilder result = new StringBuilder(body.length());
    int cursor = 0;
    for (MarkdownScanner.Span span : MarkdownScanner.protectedSpans(body)) {
      result.append(rewrite(body.substring(cursor, span.start()), assets));
      result.append(body, span.start(), span.end());
      cursor = span.end();
    }
    return result.append(rewrite(body.substring(cursor), assets)).toString();
  }

  private static Path resolveSource(Path vault, Path vaultRoot, String reference) {
    Path relative = validatedReference(reference);
    Path exact = vault.resolve(relative);
    Path candidate;
    if (Files.exists(exact) || Files.isSymbolicLink(exact)) {
      if (!Files.isRegularFile(exact)) throw new AssetValidationException(reference, "asset is not a regular file");
      candidate = exact;
    } else {
      List<Path> matches = visibleBasenameMatches(vault, relative.getFileName().toString());
      if (matches.isEmpty()) throw new AssetValidationException(reference, "missing file");
      if (matches.size() > 1) throw new AssetValidationException(reference, "ambiguous basename");
      candidate = matches.getFirst();
    }
    try {
      Path sourcePath = candidate.toRealPath();
      if (!sourcePath.startsWith(vaultRoot)) throw new AssetValidationException(reference, "resolved path is outside vault");
      Path realRelative = vaultRoot.relativize(sourcePath);
      if (hasHiddenComponent(realRelative)) throw new AssetValidationException(reference, "resolved path is hidden");
      if (!isAsset(sourcePath)) throw new AssetValidationException(reference, "unsupported extension");
      if (!Files.isRegularFile(sourcePath)) throw new AssetValidationException(reference, "asset is not a regular file");
      return sourcePath;
    } catch (IOException exception) {
      throw invalid(reference, "cannot resolve file", exception);
    }
  }

  private static Path validatedReference(String reference) {
    if (reference == null || reference.isEmpty()) throw new AssetValidationException(reference, "empty reference");
    if (reference.startsWith("/") || isWindowsAbsolute(reference)) {
      throw new AssetValidationException(reference, "absolute paths are not allowed");
    }
    for (String component : reference.split("[\\\\/]")) {
      if (component.equals("..")) throw new AssetValidationException(reference, "path traversal is not allowed");
    }
    if (reference.contains("\\")) throw new AssetValidationException(reference, "backslash paths are not allowed");
    Path path = Path.of(reference);
    for (Path component : path) {
      String value = component.toString();
      if (value.equals("..")) throw new AssetValidationException(reference, "path traversal is not allowed");
      if (value.startsWith(".")) throw new AssetValidationException(reference, "hidden paths are not allowed");
    }
    if (!isAsset(path)) throw new AssetValidationException(reference, "unsupported extension");
    return path;
  }

  private static boolean isWindowsAbsolute(String reference) {
    return reference.matches("^[A-Za-z]:[\\\\/].*")
        || (reference.length() >= 2 && reference.charAt(0) == '\\' && reference.charAt(1) == '\\');
  }

  private static List<Path> visibleBasenameMatches(Path vault, String basename) {
    try (var paths = Files.walk(vault)) {
      return paths.filter(path -> !path.equals(vault))
          .filter(path -> !hasHiddenComponent(vault.relativize(path)))
          .filter(path -> path.getFileName().toString().equals(basename))
          .filter(Files::isRegularFile)
          .sorted(Comparator.comparing(path -> vault.relativize(path).toString().replace('\\', '/')))
          .toList();
    } catch (IOException exception) {
      throw invalid(basename, "cannot search vault", exception);
    }
  }

  private static boolean hasHiddenComponent(Path path) {
    for (Path component : path) if (component.toString().startsWith(".")) return true;
    return false;
  }

  private static boolean isAsset(Path path) {
    Path name = path.getFileName();
    if (name == null) return false;
    return ASSET_EXTENSIONS.contains(extension(name.toString()));
  }

  private static String canonicalSuffix(Path sourcePath) {
    String suffix = extension(sourcePath.getFileName().toString());
    return suffix.equals(".jpeg") ? ".jpg" : suffix;
  }

  private static String extension(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
  }

  private static String sha256(byte[] content) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static AssetValidationException invalid(String reference, String reason, IOException exception) {
    return new AssetValidationException(reference, reason + ": " + exception.getMessage());
  }

  private static String rewrite(String source, Map<String, ResolvedAsset> assets) {
    Matcher matcher = WIKILINK.matcher(source);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      String target = matcher.group(2).strip();
      if (matcher.group(1).isEmpty() || !isAsset(Path.of(target))) continue;
      ResolvedAsset asset = assets.get(target);
      if (asset == null) throw new AssetValidationException(target, "asset is absent from RU asset allowlist");
      matcher.appendReplacement(result, Matcher.quoteReplacement(render(asset, target, matcher.group(3))));
    }
    return matcher.appendTail(result).toString();
  }

  private static String render(ResolvedAsset asset, String target, String label) {
    String suffix = extension(asset.outputName());
    if (suffix.equals(".mp3")) return "<audio controls src=\"" + htmlEscape(asset.publicUrl()) + "\"></audio>";
    if (suffix.equals(".mp4")) return "<video controls src=\"" + htmlEscape(asset.publicUrl()) + "\"></video>";
    if (!ASSET_EXTENSIONS.contains(suffix)) throw new AssetValidationException(target, "resolved asset has unsupported extension");
    String stem = target.substring(target.lastIndexOf('/') + 1);
    stem = stem.substring(0, stem.lastIndexOf('.'));
    String value = label == null ? "" : label.strip();
    if (value.matches("[0-9]+")) {
      return "<img src=\"" + htmlEscape(asset.publicUrl()) + "\" alt=\"" + htmlEscape(stem) + "\" width=\"" + value + "\">";
    }
    return "![" + markdownAlt(value.isEmpty() ? stem : value) + "](" + asset.publicUrl() + ")";
  }

  private static String markdownAlt(String value) { return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]"); }
  private static String htmlEscape(String value) { return value.replace("&", "&amp;").replace("'", "&#x27;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;"); }

  @FunctionalInterface
  interface AssetFileReader { byte[] read(Path path) throws IOException; }
}
