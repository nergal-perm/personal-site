package dev.eugene.astroexport.validation;

import dev.eugene.astroexport.markdown.MarkdownScanner;

final class MarkdownProtection {
  private MarkdownProtection() { }

  static String mask(String body) { return MarkdownScanner.maskProtectedContexts(body); }

  static String stripComments(String body) { return MarkdownScanner.stripMarkdownComments(body); }
}
