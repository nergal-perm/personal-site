package dev.eugene.publicationexporter.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                .append(",\"payloadDigest\":\"").append(payloadDigest).append("\"}");
        return json.toString();
    }

    private String managedTreesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < managedTrees.size(); i++) {
            if (i > 0) json.append(",");
            ManagedTreeHash tree = managedTrees.get(i);
            json.append("{\"relative\":\"").append(tree.relative())
                    .append("\",\"sha256\":\"").append(tree.sha256()).append("\"}");
        }
        return json.append("]").toString();
    }

    private String managedFilesJson() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < managedFiles.size(); i++) {
            if (i > 0) json.append(",");
            PayloadFileHash file = managedFiles.get(i);
            json.append("{\"path\":\"").append(file.path())
                    .append("\",\"sha256\":\"").append(file.sha256()).append("\"}");
        }
        return json.append("]").toString();
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
                if (Files.isDirectory(file)) continue;
                String relative = slash(siteRoot.relativize(file).toString());
                records.add(PayloadFileHash.of(relative, sha256Hex(readAllBytes(file))));
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
                boolean isDirectory = Files.isDirectory(file);
                byte[] payload = isDirectory ? new byte[0] : readAllBytes(file);
                digest.update((isDirectory ? "D" : "F").getBytes(StandardCharsets.UTF_8));
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
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> entries = walk.filter(path -> !path.equals(root)).toList();
            List<Path> sorted = new ArrayList<>(entries);
            sorted.sort(Comparator.comparing(path -> slash(root.relativize(path).toString())));
            return sorted;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static byte[] readAllBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException error) {
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
}
