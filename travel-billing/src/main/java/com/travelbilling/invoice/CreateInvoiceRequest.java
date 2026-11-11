package com.travelbilling.invoice;

import java.util.List;

import com.travelbilling.common.RequestValidationException;

/**
 * Body of POST /invoices: either { "transaction_id": "txn_1" } or { "transaction_ids": ["txn_1", ...] }.
 */
public record CreateInvoiceRequest(String transactionId, List<String> transactionIds) {

    /** Upper bound so one request can't lock an unbounded number of rows. */
    public static final int MAX_TRANSACTIONS = 500;

    /** The requested ids as one list, in request order. Exactly one of the two fields must be given. */
    public List<String> requestedIds() {
        if (transactionId != null && transactionIds != null) {
            throw new RequestValidationException(List.of("give either transaction_id or transaction_ids, not both"));
        }
        if (transactionId != null) {
            return List.of(transactionId);
        }
        if (transactionIds == null || transactionIds.isEmpty()) {
            throw new RequestValidationException(List.of("transaction_id or a non-empty transaction_ids is required"));
        }
        if (transactionIds.size() > MAX_TRANSACTIONS) {
            throw new RequestValidationException(List.of("transaction_ids: at most " + MAX_TRANSACTIONS + " per invoice"));
        }
        if (transactionIds.contains(null)) {
            throw new RequestValidationException(List.of("transaction_ids: must not contain null"));
        }
        return transactionIds;
    }
}
