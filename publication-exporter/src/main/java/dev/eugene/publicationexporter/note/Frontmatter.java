package dev.eugene.publicationexporter.note;

import java.util.Objects;
import java.util.Optional;

@Deprecated(forRemoval = true)
public final class Frontmatter {

    private final MarkdownNote note;

    private Frontmatter(MarkdownNote note) {
        this.note = Objects.requireNonNull(note, "note");
    }

    public static Frontmatter parse(String noteSource) {
        return new Frontmatter(MarkdownNote.parse(noteSource));
    }

    public String withScalarSet(String key, String value) {
        return note.sourceWithScalar(key, value);
    }

    public Optional<String> string(String key) {
        return note.string(key);
    }

    public boolean flag(String key) {
        return note.flag(key);
    }

    public String body() {
        return note.body();
    }

    public MarkdownNote.HeaderState headerState() {
        return note.headerState();
    }
}
