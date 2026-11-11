package com.travelbilling.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Body of PATCH /transactions/{id}. Only allowed while the transaction is on no invoice (else 409).
 * Every field is optional; a missing (or null) field means "leave as is".
 *
 * - type, external_id, currency are identity: they may be sent only with their CURRENT value
 *   (so a client can PATCH back the object it got from GET). A different value → 400.
 * - tax_lines, fee_lines, metadata replace the old value completely (no merging inside lists).
 */
public record PatchTransactionRequest(
    TransactionType type,
    String externalId,
    String currency,
    OffsetDateTime occurredAt,
    @Digits(integer = 15, fraction = 4) BigDecimal total,
    List<@NotNull @Valid TaxLine> taxLines,
    List<@NotNull @Valid FeeLine> feeLines,
    JsonNode metadata) {

    /** True if nothing that can change was sent. */
    public boolean changesNothing() {
        return occurredAt == null && total == null && taxLines == null && feeLines == null && metadata == null;
    }
}
