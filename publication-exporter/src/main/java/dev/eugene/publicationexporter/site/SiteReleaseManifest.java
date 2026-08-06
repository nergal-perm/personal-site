package dev.eugene.publicationexporter.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public final class SiteReleaseManifest {

    private static final int SCHEMA_VERSION = 1;

    private final List<ManagedTreeHash> managedTrees;
    private final List<PayloadFileHash> managedFiles;
    private final String payloadDigest;

    private SiteReleaseManifest(List<ManagedTreeHash> managedTrees, List<PayloadFileHash> managedFiles,
            String payloadDigest) {
        this.managedTrees = List.copyOf(managedTrees);
        this.managedFiles = List.copyOf(managedFiles);
        this.payloadDigest = Objects.requireNonNull(payloadDigest, "payloadDigest");
    }

    public static SiteReleaseManifest computeOver(Path siteRoot, List<String> payloadRoots) {
        List<ManagedTreeHash> managedTrees = new ArrayList<>();
        for (String relative : payloadRoots) {
            managedTrees.add(ManagedTreeHash.of(relative, hashTree(siteRoot.resolve(relative))));
        }
        List<PayloadFileHash> managedFiles = hashPayloadFiles(siteRoot, payloadRoots);
        String digest = computePayloadDigest(managedTrees, managedFiles);
        return new SiteReleaseManifest(managedTrees, managedFiles, digest);
    }

    public int schemaVersion() { return SCHEMA_VERSION; }
    public List<Object> selectedPages() { return List.of(); }
    public List<ManagedTreeHash> managedTrees() { return managedTrees; }
    public List<PayloadFileHash> managedFiles() { return managedFiles; }
    public int activationCount() { return 0; }
    public int deactivationCount() { return 0; }
    public String payloadDigest() { return payloadDigest; }

    public String toCanonicalJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION)
                .append(",\"selectedPages\":[]")
                .append(",\"managedTrees\":").append(managedTreesJson())
                .append(",\"managedFiles\":").append(managedFilesJson())
                .append(",\"activationCount\":0")
                .append(",\"deactivationCount\":0")
                .append(",\"payloadDigest\":");
        appendJsonString(json, payloadDigest);
        return json.append("}").toString();
    }

    private String managedTreesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < managedTrees.size(); i++) {
            if (i > 0) json.append(",");
            ManagedTreeHash tree = managedTrees.get(i);
            json.append("{\"relative\":");
            appendJsonString(json, tree.relative());
            json.append(",\"sha256\":");
            appendJsonString(json, tree.sha256());
            json.append("}");
        }
        return json.append("]").toString();
    }

    private String managedFilesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < managedFiles.size(); i++) {
            if (i > 0) json.append(",");
            PayloadFileHash file = managedFiles.get(i);
            json.append("{\"path\":");
            appendJsonString(json, file.path());
            json.append(",\"sha256\":");
            appendJsonString(json, file.sha256());
            json.append("}");
        }
        return json.append("]").toString();
    }

    static void appendJsonString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u00")
                                .append(Character.forDigit((character >> 4) & 0xF, 16))
                                .append(Character.forDigit(character & 0xF, 16));
                    } else if (Character.isSurrogate(character) && !isPairedSurrogate(value, i)) {
                        json.append("\\u")
                                .append(Character.forDigit((character >> 12) & 0xF, 16))
                                .append(Character.forDigit((character >> 8) & 0xF, 16))
                                .append(Character.forDigit((character >> 4) & 0xF, 16))
                                .append(Character.forDigit(character & 0xF, 16));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    private static boolean isPairedSurrogate(String value, int index) {
        char character = value.charAt(index);
        return Character.isHighSurrogate(character)
                && index + 1 < value.length()
                && Character.isLowSurrogate(value.charAt(index + 1))
                || Character.isLowSurrogate(character)
                && index > 0
                && Character.isHighSurrogate(value.charAt(index - 1));
    }

    private static String computePayloadDigest(List<ManagedTreeHash> managedTrees, List<PayloadFileHash> managedFiles) {
        // Mirrors check-content.mjs's `recomputed`/`canonical` objects field-for-field, with an
        // empty payloadDigest placeholder appended last, exactly matching JSON.stringify's key order.
        SiteReleaseManifest withoutDigest = new SiteReleaseManifest(managedTrees, managedFiles, "");
        return sha256Hex(withoutDigest.toCanonicalJson().getBytes(StandardCharsets.UTF_8));
    }

    private static List<PayloadFileHash> hashPayloadFiles(Path siteRoot, List<String> payloadRoots) {
        List<PayloadFileHash> records = new ArrayList<>();
        for (String relativeRoot : payloadRoots) {
            for (Path file : listTreeSortedByRelativePath(siteRoot.resolve(relativeRoot))) {
                String relative = slash(siteRoot.relativize(file).toString());
                EntryKind kind = entryKind(file, relative, "payload");
                if (kind == EntryKind.DIRECTORY) continue;
                records.add(PayloadFileHash.of(relative, sha256Hex(readAllBytes(file, relative, "payload"))));
            }
        }
        records.sort(Comparator.comparing(PayloadFileHash::path));
        return records;
    }

    private static String hashTree(Path root) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : listTreeSortedByRelativePath(root)) {
                String relative = slash(root.relativize(file).toString());
                byte[] relativeBytes = relative.getBytes(StandardCharsets.UTF_8);
                EntryKind kind = entryKind(file, relative, "tree");
                byte[] payload = kind == EntryKind.DIRECTORY ? new byte[0] : readAllBytes(file, relative, "tree");
                digest.update((kind == EntryKind.DIRECTORY ? "D" : "F").getBytes(StandardCharsets.UTF_8));
                digest.update(lengthBytes(relativeBytes.length));
                digest.update(relativeBytes);
                digest.update(lengthBytes(payload.length));
                digest.update(payload);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<Path> listTreeSortedByRelativePath(Path root) {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("managed tree is not a directory: " + root);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> entries = walk.filter(path -> !path.equals(root)).toList();
            List<Path> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(path -> slash(root.relativize(path).toString())));
            return sorted;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static EntryKind entryKind(Path entry, String relative, String context) {
        if (Files.isSymbolicLink(entry)) {
            throw new IllegalStateException("managed " + context + " contains a symlink: " + relative);
        }
        if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            return EntryKind.DIRECTORY;
        }
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
            return EntryKind.FILE;
        }
        throw new IllegalStateException("managed " + context + " contains an unsupported entry: " + relative);
    }

    static byte[] readAllBytes(Path file, String relative, String context) {
        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                var input = Channels.newInputStream(channel)) {
            return input.readAllBytes();
        } catch (IOException error) {
            if (Files.isSymbolicLink(file)) {
                throw new IllegalStateException("managed " + context + " contains a symlink: " + relative);
            }
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] lengthBytes(int length) {
        byte[] bytes = new byte[8];
        long value = length;
        for (int i = 7; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return bytes;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static String slash(String path) {
        return path.replace(java.io.File.separatorChar, '/');
    }

    private enum EntryKind {
        DIRECTORY,
        FILE
    }
}
