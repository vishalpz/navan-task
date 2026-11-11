package com.travelbilling.transaction;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A tax inside a transaction's total. Rate is optional (airport tax has none, VAT has 0.07).
 *
 * @Digits mirrors the column types (NUMERIC(19,4) / NUMERIC(9,6)), so a 5th decimal is rejected
 * with a 400 instead of Postgres silently rounding it.
 */
public record TaxLine(
    @NotBlank @Size(max = 255) String name,
    @Digits(integer = 3, fraction = 6) BigDecimal rate,
    @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {
}
