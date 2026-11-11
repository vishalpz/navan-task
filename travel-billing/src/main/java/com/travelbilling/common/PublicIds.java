package com.travelbilling.common;

import java.util.Optional;

/**
 * The database uses plain BIGINT ids; the API shows them with a prefix ("txn_42")
 * so a transaction id can never be mistaken for an invoice id.
 */
public final class PublicIds {

    private static final String TRANSACTION_PREFIX = "txn_";
    private static final String INVOICE_PREFIX = "inv_";

    private PublicIds() {
    }

    public static String transaction(long id) {
        return TRANSACTION_PREFIX + id;
    }

    public static String invoice(long id) {
        return INVOICE_PREFIX + id;
    }

    /** "txn_42" → 42. Anything else (wrong prefix, not a number) → empty, which the API treats as 404. */
    public static Optional<Long> parseTransaction(String publicId) {
        return parse(TRANSACTION_PREFIX, publicId);
    }

    public static Optional<Long> parseInvoice(String publicId) {
        return parse(INVOICE_PREFIX, publicId);
    }

    private static Optional<Long> parse(String prefix, String publicId) {
        if (publicId == null || !publicId.startsWith(prefix)) {
            return Optional.empty();
        }
        String digits = publicId.substring(prefix.length());
        if (!digits.matches("[1-9][0-9]{0,17}")) {   // positive, and always fits in a long
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(digits));
    }
}
