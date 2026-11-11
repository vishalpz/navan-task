package com.travelbilling.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Stored in hotel_details (one row, address as real columns) + hotel_extras (one row per extra).
 *
 * Extras (e.g. breakfast) are already inside the transaction total. They're descriptive
 * metadata, not fee lines, so they never show up in an invoice's fee_rollup.
 */
public record HotelMetadata(
    @NotBlank @Size(max = 255) String name,
    @NotNull @Valid Address address,
    @NotNull LocalDate checkIn,
    @NotNull LocalDate checkOut,
    @NotNull @Positive Integer nights,
    @NotNull @Digits(integer = 15, fraction = 4) BigDecimal roomRatePerNight,
    List<@NotNull @Valid Extra> extras) implements TransactionMetadata {

    public HotelMetadata {
        extras = extras == null ? List.of() : extras;
    }

    /** Same rule as the DB check constraint, checked here first so the client gets a clear 400. */
    @JsonIgnore
    @AssertTrue(message = "check_out must be after check_in")
    public boolean isCheckOutAfterCheckIn() {
        return checkIn == null || checkOut == null || checkOut.isAfter(checkIn);
    }

    public record Address(
        @NotBlank @Size(max = 255) String street,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String country) {
    }

    public record Extra(
        @NotBlank @Size(max = 255) String name,
        @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {
    }
}
