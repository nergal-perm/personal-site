package dev.eugene.astroexport.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.eugene.astroexport.review.ReviewLaunchPlanner;
import dev.eugene.astroexport.validation.PublicationDiagnostic;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable JSON response shape for Obsidian bridge commands. */
public final class BridgeResponse {
  public static final int SCHEMA_VERSION = 3;
  private static final ObjectMapper JSON = new ObjectMapper();

  private final LinkedHashMap<String, Object> payload;

  private BridgeResponse(LinkedHashMap<String, Object> payload) {
    this.payload = payload;
  }

  public String toJson() {
    try {
      return JSON.writeValueAsString(payload);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("bridge response could not be serialized", error);
    }
  }

  public Map<String, Object> payload() {
    return Map.copyOf(payload);
  }

  public static Builder builder(String command) {
    return new Builder(command);
  }

  public static final class Builder {
    private final String command;
    private boolean ok;
    private String status;
    private String note;
    private String collection;
    private String publicId;
    private String reviewDirectory;
    private String pairFreshness;
    private String translationStatus;
    private String candidateState;
    private String approvedSnapshotState;
    private String semanticReferencesState;
    private String releaseState;
    private ReviewLaunchPlanner.ReviewPlan reviewPlan;
    private List<PublicationDiagnostic> diagnostics = List.of();
    private List<PublicationDiagnostic> workspaceHealth = List.of();
    private String jobId;
    private Map<String, Integer> summary;
    private Integer updated;
    private Integer unchanged;
    private Integer uncertain;

    private Builder(String command) {
      this.command = command;
    }

    public Builder ok(boolean ok) {
      this.ok = ok;
      return this;
    }

    public Builder status(String status) {
      this.status = status;
      return this;
    }

    public Builder note(String note) {
      this.note = note;
      return this;
    }

    public Builder identity(AstroExportCommand.PublicationIdentity identity) {
      if (identity != null) {
        this.collection = identity.collection();
        this.publicId = identity.publicId();
        this.reviewDirectory = identity.reviewDirectory() == null ? null : identity.reviewDirectory().toString();
      }
      return this;
    }

    public Builder pairFreshness(String pairFreshness) {
      this.pairFreshness = pairFreshness;
      return this;
    }

    public Builder translationStatus(String translationStatus) {
      this.translationStatus = translationStatus;
      return this;
    }

    public Builder candidateState(String candidateState) {
      this.candidateState = candidateState;
      return this;
    }

    public Builder approvedSnapshotState(String approvedSnapshotState) {
      this.approvedSnapshotState = approvedSnapshotState;
      return this;
    }

    public Builder semanticReferencesState(String semanticReferencesState) {
      this.semanticReferencesState = semanticReferencesState;
      return this;
    }

    public Builder releaseState(String releaseState) {
      this.releaseState = releaseState;
      return this;
    }

    public Builder reviewPlan(ReviewLaunchPlanner.ReviewPlan reviewPlan) {
      this.reviewPlan = reviewPlan;
      return this;
    }

    public Builder diagnostics(List<PublicationDiagnostic> diagnostics) {
      this.diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
      return this;
    }

    public Builder workspaceHealth(List<PublicationDiagnostic> workspaceHealth) {
      this.workspaceHealth = workspaceHealth == null ? List.of() : List.copyOf(workspaceHealth);
      return this;
    }

    public Builder jobId(String jobId) {
      this.jobId = jobId;
      return this;
    }

    public Builder summary(Map<String, Integer> summary) {
      this.summary = summary == null ? null : new LinkedHashMap<>(summary);
      return this;
    }

    public Builder updated(Integer updated) {
      this.updated = updated;
      return this;
    }

    public Builder unchanged(Integer unchanged) {
      this.unchanged = unchanged;
      return this;
    }

    public Builder uncertain(Integer uncertain) {
      this.uncertain = uncertain;
      return this;
    }

    public BridgeResponse build() {
      LinkedHashMap<String, Object> values = new LinkedHashMap<>();
      values.put("schemaVersion", SCHEMA_VERSION);
      values.put("command", command);
      values.put("ok", ok);
      values.put("status", status);
      values.put("note", note);
      values.put("collection", collection);
      values.put("publicId", publicId);
      values.put("reviewDirectory", reviewDirectory);
      values.put("pairFreshness", pairFreshness);
      values.put("translationStatus", translationStatus);
      values.put("candidateState", candidateState);
      values.put("approvedSnapshotState", approvedSnapshotState);
      values.put("semanticReferencesState", semanticReferencesState);
      values.put("releaseState", releaseState);
      values.put("reviewPlan", reviewPlanPayload(reviewPlan));
      values.put("diagnostics", diagnosticPayloads(diagnostics));
      values.put("workspaceHealth", diagnosticPayloads(workspaceHealth));
      values.put("jobId", jobId);
      values.put("summary", summary);
      values.put("updated", updated);
      values.put("unchanged", unchanged);
      values.put("uncertain", uncertain);
      return new BridgeResponse(values);
    }

    private static List<Map<String, Object>> diagnosticPayloads(List<PublicationDiagnostic> diagnostics) {
      return diagnostics.stream().map(diagnostic -> {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", diagnostic.field());
        item.put("message", diagnostic.message());
        item.put("blocking", diagnostic.blocking());
        return item;
      }).toList();
    }

    private static Map<String, Object> reviewPlanPayload(
        ReviewLaunchPlanner.ReviewPlan plan) {
      if (plan == null) {
        return null;
      }
      LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
      payload.put("baselineState", plan.baselineState());
      payload.put("targets",
          plan.targets().stream().map(BridgeResponse.Builder::reviewTargetPayload).toList());
      return payload;
    }

    private static Map<String, Object> reviewTargetPayload(
        ReviewLaunchPlanner.ReviewTarget target) {
      LinkedHashMap<String, Object> item = new LinkedHashMap<>();
      item.put("language", target.language());
      item.put("proposedPath", target.proposedPath().toString());
      item.put("publishedPath",
          target.publishedPath() == null ? null : target.publishedPath().toString());
      return item;
    }
  }
}
