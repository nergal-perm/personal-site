package dev.eugene.publicationexporter.bridge;

import java.io.UncheckedIOException;

public final class IoFailureMessages {

    private IoFailureMessages() {
    }

    public static String describe(String operation, UncheckedIOException failure) {
        String detail = failure.getCause().getMessage();
        return detail == null || detail.isBlank() ? operation + "." : operation + ": " + detail;
    }
}
