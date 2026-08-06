package dev.eugene.publicationexporter.site;

import java.nio.file.Path;

public final class UnsafeManagedSiteEntryException extends IllegalStateException {

    private UnsafeManagedSiteEntryException(String message) {
        super(message);
    }

    static UnsafeManagedSiteEntryException treeIsNotDirectory(Path root) {
        return new UnsafeManagedSiteEntryException("managed tree is not a directory: " + root);
    }

    static UnsafeManagedSiteEntryException symbolicLink(String context, String relative) {
        return new UnsafeManagedSiteEntryException(
                "managed " + context + " contains a symlink: " + relative);
    }

    static UnsafeManagedSiteEntryException unsupportedEntry(String context, String relative) {
        return new UnsafeManagedSiteEntryException(
                "managed " + context + " contains an unsupported entry: " + relative);
    }
}
