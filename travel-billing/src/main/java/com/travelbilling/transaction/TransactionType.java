package com.travelbilling.transaction;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The four transaction types. Each knows its JSON/DB code and which metadata class
 * its "metadata" object must be read into.
 */
public enum TransactionType {
    FLIGHT("flight", FlightMetadata.class),
    HOTEL("hotel", HotelMetadata.class),
    RAIL("rail", RailMetadata.class),
    NAVAN_FEE("navan_fee", NavanFeeMetadata.class);

    private final String code;
    private final Class<? extends TransactionMetadata> metadataClass;

    TransactionType(String code, Class<? extends TransactionMetadata> metadataClass) {
        this.code = code;
        this.metadataClass = metadataClass;
    }

    /** @JsonValue: Jackson writes and reads this enum as "flight", "navan_fee", ... (unknown value → 400). */
    @JsonValue
    public String code() {
        return code;
    }

    public Class<? extends TransactionMetadata> metadataClass() {
        return metadataClass;
    }

    /** For reading the `type` column back from the database. */
    public static TransactionType fromCode(String code) {
        for (TransactionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown transaction type in database: " + code);
    }
}
