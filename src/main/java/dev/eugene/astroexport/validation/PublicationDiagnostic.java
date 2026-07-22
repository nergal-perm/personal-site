package dev.eugene.astroexport.validation;

public record PublicationDiagnostic(String field, String message, boolean blocking) {
  public PublicationDiagnostic(String field, String message) {
    this(field, message, true);
  }
}
