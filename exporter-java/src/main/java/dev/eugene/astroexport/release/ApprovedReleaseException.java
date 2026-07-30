package dev.eugene.astroexport.release;

public final class ApprovedReleaseException extends RuntimeException {
  private final String code;
  private final String sourcePath;

  public ApprovedReleaseException(String code, String sourcePath, String message) {
    super(message);
    this.code = code;
    this.sourcePath = sourcePath;
  }

  public ApprovedReleaseException(
      String code,
      String sourcePath,
      String message,
      Throwable cause) {
    super(message, cause);
    this.code = code;
    this.sourcePath = sourcePath;
  }

  public String code() {
    return code;
  }

  public String sourcePath() {
    return sourcePath;
  }
}
