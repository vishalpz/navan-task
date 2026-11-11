package com.travelbilling.invoice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.travelbilling.transaction.TransactionType;

/**
 * One transaction as it looked when it was invoiced: the photocopy.
 *
 * The nested parts (tax lines, fee lines, metadata) are kept as frozen JSON text. They're stored
 * in JSONB columns and returned exactly as copied, never re-read from the transaction tables.
 *
 * @param transactionId reference only, for "which transaction was this?". Never used to look up amounts.
 * @param feeKind       only set for navan_fee lines (trip_fee / agent_call_fee)
 */
public record InvoiceLine(
    long transactionId,
    TransactionType type,
    String feeKind,
    String externalId,
    String currency,
    OffsetDateTime occurredAt,
    BigDecimal total,
    String taxLinesJson,
    String feeLinesJson,
    String metadataJson) {

    /** The key this line adds up under in "totals": the type, except Navan fees split by kind. */
    public String totalsKey() {
        return feeKind != null ? feeKind : type.code();
    }
}
