package com.travelbilling.transaction;

/** A transaction with this external_id already exists. Mapped to 409 with the existing id. */
public class DuplicateExternalIdException extends RuntimeException {

    private final long existingId;

    public DuplicateExternalIdException(long existingId) {
        super("external_id already exists on transaction " + existingId);
        this.existingId = existingId;
    }

    public long existingId() {
        return existingId;
    }
}
