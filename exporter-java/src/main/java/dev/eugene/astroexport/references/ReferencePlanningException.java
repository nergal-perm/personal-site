package dev.eugene.astroexport.references;

public final class ReferencePlanningException extends RuntimeException {
  private final String code;

  public ReferencePlanningException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
