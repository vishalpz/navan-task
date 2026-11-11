package com.travelbilling.transaction;

import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelbilling.common.FieldPaths;
import com.travelbilling.common.RequestValidationException;

import jakarta.validation.Validator;

/**
 * Turns the raw "metadata" JSON into the right record for the transaction's type, then runs
 * that record's validation annotations. Any problem → RequestValidationException (400).
 */
@Component
public class MetadataParser {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public MetadataParser(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public TransactionMetadata parse(TransactionType type, JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new RequestValidationException(List.of("metadata: must be an object"));
        }

        TransactionMetadata metadata;
        try {
            // Same ObjectMapper as the rest of the app: snake_case names, decimals as BigDecimal.
            metadata = objectMapper.treeToValue(json, type.metadataClass());
        } catch (JsonProcessingException e) {
            String path = e instanceof JsonMappingException jme ? FieldPaths.fromJson(jme) : "";
            throw new RequestValidationException(
                List.of("metadata" + (path.isEmpty() ? "" : "." + path) + ": invalid value"));
        }

        List<String> errors = validator.validate(metadata).stream()
            .map(v -> "metadata." + FieldPaths.fromJavaPath(v.getPropertyPath().toString()) + ": " + v.getMessage())
            .sorted()
            .toList();
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        return metadata;
    }
}
