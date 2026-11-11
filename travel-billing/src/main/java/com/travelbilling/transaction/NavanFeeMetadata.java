package com.travelbilling.transaction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Navan's own charge. Stored in navan_fee_details.
 * related_external_id is informational only. It isn't checked, because a fee may arrive before the booking it refers to.
 */
public record NavanFeeMetadata(
    @NotNull @Pattern(regexp = "trip_fee|agent_call_fee", message = "must be trip_fee or agent_call_fee")
    String feeKind,
    @Size(max = 500) String description,
    @Size(max = 255) String relatedExternalId) implements TransactionMetadata {
}
