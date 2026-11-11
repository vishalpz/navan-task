package com.travelbilling.transaction;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A supplier fee inside a transaction's total (YQ, booking fee). Not the same thing as a Navan fee. */
public record FeeLine(
    @NotBlank @Size(max = 255) String name,
    @NotNull @Digits(integer = 15, fraction = 4) BigDecimal amount) {
}
