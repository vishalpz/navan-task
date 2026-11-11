package com.travelbilling.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.travelbilling.common.PublicIds;
import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.TaxLine;
import com.travelbilling.transaction.Transaction;
import com.travelbilling.transaction.TransactionMetadata;
import com.travelbilling.transaction.TransactionType;

/** JSON shape of a transaction. Same fields as the request, plus id and timestamps. */
public record TransactionResponse(
    String id,
    TransactionType type,
    String externalId,
    String currency,
    OffsetDateTime occurredAt,
    BigDecimal total,
    List<TaxLine> taxLines,
    List<FeeLine> feeLines,
    TransactionMetadata metadata,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
            PublicIds.transaction(t.id()), t.type(), t.externalId(), t.currency(), t.occurredAt(), t.total(),
            t.taxLines(), t.feeLines(), t.metadata(), t.createdAt(), t.updatedAt());
    }
}
