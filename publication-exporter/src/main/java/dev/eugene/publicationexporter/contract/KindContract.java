package dev.eugene.publicationexporter.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public final class KindContract {

    private final String collection;
    private final String contentType;
    private final List<FieldContract> requiredFields;
    private final List<FieldContract> optionalFields;
    private final List<String> blockedFields;
    private final List<String> structuredBody;

    private KindContract(
            String collection,
            String contentType,
            List<FieldContract> requiredFields,
            List<FieldContract> optionalFields,
            List<String> blockedFields,
            List<String> structuredBody) {
        this.collection = Objects.requireNonNull(collection, "collection");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        this.requiredFields = List.copyOf(Objects.requireNonNull(requiredFields, "requiredFields"));
        this.optionalFields = List.copyOf(Objects.requireNonNull(optionalFields, "optionalFields"));
        this.blockedFields = List.copyOf(Objects.requireNonNull(blockedFields, "blockedFields"));
        this.structuredBody = List.copyOf(Objects.requireNonNull(structuredBody, "structuredBody"));
    }

    public static KindContract of(
            String collection, String contentType, List<FieldContract> requiredFields, List<String> structuredBody) {
        return new KindContract(collection, contentType, requiredFields, List.of(), List.of(), structuredBody);
    }

    public static KindContract of(
            String collection,
            String contentType,
            List<FieldContract> requiredFields,
            List<FieldContract> optionalFields,
            List<String> blockedFields,
            List<String> structuredBody) {
        return new KindContract(
                collection,
                contentType,
                requiredFields,
                optionalFields,
                blockedFields,
                structuredBody);
    }

    @JsonProperty("collection")
    public String collection() {
        return collection;
    }

    @JsonProperty("contentType")
    public String contentType() {
        return contentType;
    }

    @JsonProperty("requiredFields")
    public List<FieldContract> requiredFields() {
        return requiredFields;
    }

    @JsonProperty("optionalFields")
    public List<FieldContract> optionalFields() {
        return optionalFields;
    }

    @JsonProperty("blockedFields")
    public List<String> blockedFields() {
        return blockedFields;
    }

    @JsonProperty("structuredBody")
    public List<String> structuredBody() {
        return structuredBody;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof KindContract that)) {
            return false;
        }
        return collection.equals(that.collection) && contentType.equals(that.contentType)
                && requiredFields.equals(that.requiredFields)
                && optionalFields.equals(that.optionalFields)
                && blockedFields.equals(that.blockedFields)
                && structuredBody.equals(that.structuredBody);
    }

    @Override
    public int hashCode() {
        return Objects.hash(collection, contentType, requiredFields, optionalFields, blockedFields, structuredBody);
    }

    @Override
    public String toString() {
        return "KindContract[collection=" + collection + ", contentType=" + contentType
                + ", requiredFields=" + requiredFields
                + ", optionalFields=" + optionalFields
                + ", blockedFields=" + blockedFields
                + ", structuredBody=" + structuredBody + "]";
    }
}
