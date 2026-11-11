package com.travelbilling.api;

import java.util.List;

/**
 * Every error response has this shape. Null fields are left out of the JSON
 * (default-property-inclusion: non_null), so e.g. a 409 duplicate is just
 * { "error": "duplicate_external_id", "message": "...", "existing_id": "txn_1" }.
 */
public record ApiError(String error, String message, List<String> details, String existingId) {

    public static ApiError of(String error, String message) {
        return new ApiError(error, message, null, null);
    }
}
