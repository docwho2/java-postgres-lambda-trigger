package demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import demo.PostgresAbstractTrigger.TG_OP;

/**
 * Validated envelope shared by direct Lambda and SQS row-change delivery.
 *
 * <p>Only top-level metadata is considered: a similarly named field inside a row cannot
 * supply a missing operation or table. Row images are not schema-specific here.
 *
 * @param operation PostgreSQL row operation
 * @param tableName nonblank source table name
 * @param oldRecord pre-change object, or JSON null for INSERT
 * @param newRecord post-change object, or JSON null for DELETE
 */
record PostgresChange(TG_OP operation, String tableName, JsonNode oldRecord, JsonNode newRecord) {

    /**
     * Validates the trigger envelope and the row images required by its operation.
     *
     * @param json decoded event; must be a JSON object with top-level metadata
     * @return the validated event, normalizing absent unused images to JSON null
     * @throws IllegalArgumentException if metadata, operation, or row-image shapes are invalid
     */
    static PostgresChange fromJson(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new IllegalArgumentException("Trigger event must be a JSON object");
        }
        var operation = TG_OP.valueOf(requiredText(json, "TG_OP"));
        var table = requiredText(json, "TG_TABLE_NAME");
        var oldRecord = json.path("old");
        var newRecord = json.path("new");
        validateImage(oldRecord, "old", operation != TG_OP.INSERT);
        validateImage(newRecord, "new", operation != TG_OP.DELETE);
        return new PostgresChange(operation, table,
                oldRecord.isMissingNode() ? NullNode.getInstance() : oldRecord,
                newRecord.isMissingNode() ? NullNode.getInstance() : newRecord);
    }

    /**
     * Reads textual metadata without coercing numbers or traversing nested records.
     *
     * @param json event envelope
     * @param field top-level property name
     * @return the nonblank text value
     * @throws IllegalArgumentException if the property is missing, non-textual, or blank
     */
    private static String requiredText(JsonNode json, String field) {
        var value = json.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("Trigger event requires nonblank " + field);
        }
        return value.asText();
    }

    /**
     * Checks whether a row image is present for operations that require it.
     *
     * @param image supplied row image, including a missing-node marker if absent
     * @param field image name used in error messages
     * @param required whether this operation requires an object image
     * @throws IllegalArgumentException if a required image is not an object or an unused image is populated
     */
    private static void validateImage(JsonNode image, String field, boolean required) {
        if (required ? !image.isObject() : !(image.isNull() || image.isMissingNode())) {
            throw new IllegalArgumentException("Invalid " + field + " image for trigger operation");
        }
    }
}
