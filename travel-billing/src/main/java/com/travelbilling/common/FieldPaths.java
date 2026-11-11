package com.travelbilling.common;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * Turns "where did the error happen" into the API's snake_case field names,
 * so a client sees "tax_lines[0].amount", not the Java name "taxLines[0].amount".
 */
public final class FieldPaths {

    private FieldPaths() {
    }

    /** Java bean path → snake_case: "metadata.coupons[0].flightNumber" → "metadata.coupons[0].flight_number". */
    public static String fromJavaPath(String javaPath) {
        return javaPath.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** Jackson already reports JSON names; just join them: [tax_lines, 0, amount] → "tax_lines[0].amount". */
    public static String fromJson(JsonMappingException e) {
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getFieldName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
