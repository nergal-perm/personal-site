package dev.eugene.astroexport.fs;

import ewc.utilities.testableio.core.QueryId;
import ewc.utilities.testableio.core.SourceId;
import ewc.utilities.testableio.core.StubFacade;
import ewc.utilities.testableio.exceptions.UnconfiguredStubException;
import ewc.utilities.testableio.responses.ExceptionResponse;
import ewc.utilities.testableio.responses.RawResponse;
import ewc.utilities.testableio.responses.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Nulled {@link FileSystem}: no real I/O. Each path is a {@link SourceId} and each operation a
 * {@link QueryId}, so responses are configured per (path, operation) through the testable-io
 * stub facade. Configuration is expressed in the filesystem's own language ({@link #withFile},
 * {@link #withReadError}, {@link #withRealPath}); the raw {@link Response} overload is the escape
 * hatch for sequences (e.g. "the 5th read fails").
 *
 * <p>Loud, safe defaults keep a bare {@code createNull()} usable: nothing exists (empty world),
 * and {@code toRealPath} is identity until a symlink target is configured.
 */
public final class NullFileSystem implements FileSystem {

    private static final QueryId EXISTS = new QueryId("isRegularFile");
    private static final QueryId REAL_PATH = new QueryId("toRealPath");
    private static final QueryId READ = new QueryId("readString");

    private final StubFacade responses = StubFacade.basic();

    // ---- configuration, in the filesystem's own language ----

    /** Make {@code path} a regular file whose contents are {@code content}. */
    public NullFileSystem withFile(Path path, String content) {
        return withFile(path, new RawResponse(content));
    }

    /** Make {@code path} a regular file whose reads are driven by {@code readResponse}. */
    public NullFileSystem withFile(Path path, Response readResponse) {
        responses.setStubForQuerySource(source(path), EXISTS, new RawResponse(Boolean.TRUE));
        responses.setStubForQuerySource(source(path), READ, readResponse);
        return this;
    }

    /** Make {@code path} exist but fail on read with {@code error}. */
    public NullFileSystem withReadError(Path path, IOException error) {
        return withFile(path, new ExceptionResponse(new UncheckedIOException(error)));
    }

    /** Resolve {@code path} to {@code resolved} (e.g. to simulate a symlink escaping the vault). */
    public NullFileSystem withRealPath(Path path, Path resolved) {
        responses.setStubForQuerySource(source(path), REAL_PATH, new RawResponse(resolved));
        return this;
    }

    // ---- FileSystem operations ----

    @Override
    public boolean isRegularFile(Path path) {
        try {
            return responses.next(source(path), EXISTS, Boolean.class);
        } catch (UnconfiguredStubException unconfigured) {
            return false; // empty world: nothing exists unless configured
        }
    }

    @Override
    public Path toRealPath(Path path) {
        try {
            return responses.next(source(path), REAL_PATH, Path.class);
        } catch (UnconfiguredStubException unconfigured) {
            return path; // identity until a symlink target is configured
        }
    }

    @Override
    public String readString(Path path) throws IOException {
        try {
            return responses.next(source(path), READ, String.class);
        } catch (UncheckedIOException wrapped) {
            throw wrapped.getCause();
        }
    }

    private static SourceId source(Path path) {
        return new SourceId(path.toString());
    }
}
