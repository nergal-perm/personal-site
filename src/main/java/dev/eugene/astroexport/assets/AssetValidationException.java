package dev.eugene.astroexport.assets;

/** Reports a user-facing invalid asset reference. */
public final class AssetValidationException extends IllegalArgumentException {
  private final String reference;
  private final String reason;

  public AssetValidationException(String reference, String reason) {
    super(reference + ": " + reason);
    this.reference = reference;
    this.reason = reason;
  }

  public String reference() { return reference; }
  public String reason() { return reason; }
}
