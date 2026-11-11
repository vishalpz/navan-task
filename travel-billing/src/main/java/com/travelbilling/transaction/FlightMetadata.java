package com.travelbilling.transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Stored in flight_details (one row) + flight_coupons (one row per segment).
 *
 * "coupons must not be empty" is checked AFTER the duplicate check (see TransactionService),
 * so the fixture's duplicate_flight with coupons: [] gets 409, not 400.
 */
public record FlightMetadata(
    @NotBlank @Size(max = 100) String origin,
    @NotBlank @Size(max = 100) String destination,
    @NotNull @Digits(integer = 15, fraction = 4) BigDecimal fare,
    @NotEmpty List<@NotNull @Valid Coupon> coupons) implements TransactionMetadata {

    public record Coupon(
        @Size(max = 10) String carrier,
        @NotBlank @Size(max = 20) String flightNumber,
        @NotBlank @Size(max = 100) String from,
        @NotBlank @Size(max = 100) String to,
        @NotNull OffsetDateTime departure,
        @NotNull OffsetDateTime arrival) {
    }
}
