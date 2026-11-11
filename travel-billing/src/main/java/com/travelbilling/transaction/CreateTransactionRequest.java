package com.travelbilling.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /transactions.
 *
 * The controller's @Valid checks only these envelope fields, the minimum needed to insert the
 * header row and do the duplicate check.
 *
 * `metadata` stays raw JSON (JsonNode) here on purpose: its shape depends on `type`, and its
 * rules (e.g. at least one coupon) are checked by the service only AFTER the duplicate check.
 */
public record CreateTransactionRequest(
    @NotNull TransactionType type,
    @NotBlank @Size(max = 255) String externalId,
    @NotNull @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO currency code") String currency,
    @NotNull OffsetDateTime occurredAt,
    // Gross amount (includes tax + fees). Deliberately NOT checked against the sum of parts:
    // rail has no fare, hotel extras are inside the room total, etc.
    @NotNull @Digits(integer = 15, fraction = 4) BigDecimal total,
    // Required by the brief, but [] is fine when there's no tax / no fee.
    @NotNull List<@NotNull @Valid TaxLine> taxLines,
    @NotNull List<@NotNull @Valid FeeLine> feeLines,
    @NotNull JsonNode metadata) {
}
