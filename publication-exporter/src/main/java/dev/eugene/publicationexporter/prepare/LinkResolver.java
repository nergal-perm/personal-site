package dev.eugene.publicationexporter.prepare;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LinkResolver {

    private static final Pattern WIKILINK =
            Pattern.compile("(!?)\\[\\[([^\\[\\]|#]+)(?:#[^\\[\\]|]*)?(?:\\|([^\\[\\]]+))?]]");
    private LinkResolver() {
    }

    public static LinkResolutionOutcome resolve(String body, PublicNoteIndex knownNotes) {
        StringBuilder output = new StringBuilder(body.length());
        List<LinkOccurrence> occurrences = new ArrayList<>();
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            Matcher link = nextLink(body, cursor);
            if (protectedSpanBeforeLink(protectedSpan, link)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (link != null) {
                Optional<String> blockedTarget = appendLink(
                        body, output, cursor, link, knownNotes, occurrences);
                if (blockedTarget.isPresent()) {
                    return LinkResolutionOutcome.blockedTransclusion(blockedTarget.get());
                }
                cursor = link.end();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return LinkResolutionOutcome.resolved(output.toString(), occurrences);
    }

    private static Matcher nextLink(String body, int cursor) {
        Matcher matcher = WIKILINK.matcher(body);
        return matcher.find(cursor) ? matcher : null;
    }

    private static boolean protectedSpanBeforeLink(ProtectedRegionScanner.ProtectedSpan protectedSpan, Matcher link) {
        return protectedSpan != null && (link == null || protectedSpan.start() <= link.start());
    }

    private static int copyProtectedSpan(
            String body, StringBuilder output, int cursor, ProtectedRegionScanner.ProtectedSpan protectedSpan) {
        output.append(body, cursor, protectedSpan.end());
        return protectedSpan.end();
    }

    private static Optional<String> appendLink(
            String body, StringBuilder output, int cursor, Matcher link, PublicNoteIndex knownNotes,
            List<LinkOccurrence> occurrences) {
        output.append(body, cursor, link.start());
        boolean isEmbed = !link.group(1).isEmpty();
        String target = link.group(2).strip();
        String label = labelFor(link, target);
        if (isEmbed && AssetTargets.isAssetTarget(target)) {
            output.append(link.group());
            return Optional.empty();
        }
        Optional<PublicNoteIndex.NoteReference> reference = knownNotes.referenceFor(target);
        if (reference.isPresent()) {
            if (isEmbed) {
                appendAdmittedEmbed(output, label, reference.get());
            } else {
                appendAdmittedNonEmbed(output, target, label, reference.get(), occurrences);
            }
            return Optional.empty();
        }
        if (isEmbed) {
            return Optional.of(lastPathSegment(target));
        }
        return appendUnresolvedLink(output, target, label, occurrences);
    }

    private static void appendAdmittedEmbed(
            StringBuilder output, String label, PublicNoteIndex.NoteReference reference) {
        output.append('[').append(label).append("](").append(reference.route()).append(')');
    }

    private static void appendAdmittedNonEmbed(
            StringBuilder output, String target, String label, PublicNoteIndex.NoteReference reference,
            List<LinkOccurrence> occurrences) {
        output.append('[');
        int spanStart = output.length();
        output.append(label);
        int spanEnd = output.length();
        output.append("](ref:").append(reference.sourceId()).append(')');
        occurrences.add(new LinkOccurrence(
                lastPathSegment(target), label, Optional.of(reference.route()), spanStart, spanEnd));
    }

    private static Optional<String> appendUnresolvedLink(
            StringBuilder output, String target, String label, List<LinkOccurrence> occurrences) {
        int spanStart = output.length();
        output.append(label);
        int spanEnd = output.length();
        occurrences.add(new LinkOccurrence(lastPathSegment(target), label, Optional.empty(), spanStart, spanEnd));
        return Optional.empty();
    }

    private static String labelFor(Matcher link, String target) {
        String alias = link.group(3);
        return alias != null ? alias.strip() : lastPathSegment(target);
    }

    private static String lastPathSegment(String target) {
        int lastSlash = target.lastIndexOf('/');
        return lastSlash >= 0 ? target.substring(lastSlash + 1) : target;
    }

}
