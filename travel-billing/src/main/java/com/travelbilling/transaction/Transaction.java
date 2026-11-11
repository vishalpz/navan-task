package com.travelbilling.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** A stored transaction, fully loaded (header + tax lines + fee lines + type-specific metadata). */
public record Transaction(
    long id,
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
}
