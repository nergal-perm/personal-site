package dev.eugene.publicationexporter.prepare;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import java.util.Set;

public final class LinkResolver {

    private static final Pattern WIKILINK =
            Pattern.compile("(!?)\\[\\[([^\\[\\]|#]+)(?:#[^\\[\\]|]*)?(?:\\|([^\\[\\]]+))?]]");
    private LinkResolver() {
    }

    public static LinkResolutionOutcome resolve(String body, PublicNoteIndex knownNotes) {
        StringBuilder output = new StringBuilder(body.length());
        Set<String> privateTargetStems = new LinkedHashSet<>();
        int cursor = 0;
        while (cursor < body.length()) {
            ProtectedRegionScanner.ProtectedSpan protectedSpan = ProtectedRegionScanner.nextProtectedSpan(body, cursor);
            Matcher link = nextLink(body, cursor);
            if (protectedSpanBeforeLink(protectedSpan, link)) {
                cursor = copyProtectedSpan(body, output, cursor, protectedSpan);
            } else if (link != null) {
                Optional<String> blockedTarget = appendLink(
                        body, output, cursor, link, knownNotes, privateTargetStems);
                if (blockedTarget.isPresent()) {
                    return LinkResolutionOutcome.blockedTransclusion(blockedTarget.get());
                }
                cursor = link.end();
            } else {
                break;
            }
        }
        output.append(body, cursor, body.length());
        return LinkResolutionOutcome.resolved(output.toString(), privateTargetStems);
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
            Set<String> privateTargetStems) {
        output.append(body, cursor, link.start());
        boolean isEmbed = !link.group(1).isEmpty();
        String target = link.group(2).strip();
        String label = labelFor(link, target);
        if (isEmbed && AssetTargets.isAssetTarget(target)) {
            output.append(link.group());
            return Optional.empty();
        }
        Optional<String> route = knownNotes.routeFor(target);
        if (route.isPresent()) {
            output.append('[').append(label).append("](").append(route.get()).append(')');
            return Optional.empty();
        }
        if (isEmbed) {
            return Optional.of(lastPathSegment(target));
        }
        privateTargetStems.add(target);
        output.append(label);
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
