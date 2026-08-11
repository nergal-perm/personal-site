package dev.eugene.publicationexporter.site;

public final class YamlScalar {

    private YamlScalar() {
    }

    public static String doubleQuoted(String value) {
        StringBuilder scalar = new StringBuilder();
        SiteReleaseManifest.appendJsonString(scalar, value);
        return scalar.toString();
    }
}
