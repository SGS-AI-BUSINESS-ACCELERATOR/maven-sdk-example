package com.example.datastudio.model;

import org.json.JSONObject;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Model representing a processed document result.
 *
 * <p>This class provides a type-safe wrapper around the JSON response
 * from the DataStudio API. It extracts and validates the common fields
 * returned after document processing.
 *
 * <p>Example usage:
 * <pre>{@code
 * JSONObject result = sdk.getResult(processId);
 * ProcessedDocument doc = ProcessedDocument.fromJson(result);
 *
 * System.out.println("Extracted Text: " + doc.extractedText());
 * System.out.println("Confidence: " + doc.confidenceScore().orElse(0.0));
 * doc.structuredData().ifPresent(data ->
 *     System.out.println("Invoice Number: " + data.get("invoice_number"))
 * );
 * }</pre>
 */
public record ProcessedDocument(
        String processId,
        String extractedText,
        Integer pageCount,
        Double confidenceScore,
        String resultUrl,
        String status,
        Map<String, Object> structuredData,
        Instant processedAt
) {

    /**
     * Create a ProcessedDocument from a JSON response.
     *
     * @param json The JSON response from getResult()
     * @return A new ProcessedDocument instance
     */
    public static ProcessedDocument fromJson(JSONObject json) {
        return new ProcessedDocument(
                getStringOrNull(json, "process_id"),
                getStringOrNull(json, "extracted_text"),
                getIntegerOrNull(json, "pages"),
                getDoubleOrNull(json, "confidence_score"),
                getStringOrNull(json, "url"),
                getStringOrNull(json, "status"),
                extractStructuredData(json),
                Instant.now()
        );
    }

    /**
     * Get the extracted text, or empty string if not available.
     */
    public String extractedTextOrEmpty() {
        return extractedText != null ? extractedText : "";
    }

    /**
     * Get the confidence score as an Optional.
     */
    public Optional<Double> confidenceScoreOpt() {
        return Optional.ofNullable(confidenceScore);
    }

    /**
     * Get the page count as an Optional.
     */
    public Optional<Integer> pageCountOpt() {
        return Optional.ofNullable(pageCount);
    }

    /**
     * Get the result URL as an Optional.
     */
    public Optional<String> resultUrlOpt() {
        return Optional.ofNullable(resultUrl);
    }

    /**
     * Get the structured data as an Optional.
     */
    public Optional<Map<String, Object>> structuredDataOpt() {
        return Optional.ofNullable(structuredData);
    }

    /**
     * Check if the confidence score meets a threshold.
     *
     * @param threshold The minimum confidence threshold (0.0 to 1.0)
     * @return true if confidence meets or exceeds threshold
     */
    public boolean meetsConfidenceThreshold(double threshold) {
        return confidenceScore != null && confidenceScore >= threshold;
    }

    /**
     * Get a specific field from structured data.
     *
     * @param fieldName The field name to retrieve
     * @return Optional containing the field value
     */
    public Optional<Object> getStructuredField(String fieldName) {
        if (structuredData == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(structuredData.get(fieldName));
    }

    /**
     * Get a specific field from structured data as a String.
     *
     * @param fieldName The field name to retrieve
     * @return Optional containing the field value as String
     */
    public Optional<String> getStructuredFieldAsString(String fieldName) {
        return getStructuredField(fieldName)
                .map(Object::toString);
    }

    private static String getStringOrNull(JSONObject json, String key) {
        return json.has(key) && !json.isNull(key) ? json.getString(key) : null;
    }

    private static Integer getIntegerOrNull(JSONObject json, String key) {
        return json.has(key) && !json.isNull(key) ? json.getInt(key) : null;
    }

    private static Double getDoubleOrNull(JSONObject json, String key) {
        return json.has(key) && !json.isNull(key) ? json.getDouble(key) : null;
    }

    private static Map<String, Object> extractStructuredData(JSONObject json) {
        if (!json.has("structured_data") || json.isNull("structured_data")) {
            return null;
        }
        return json.getJSONObject("structured_data").toMap();
    }
}
