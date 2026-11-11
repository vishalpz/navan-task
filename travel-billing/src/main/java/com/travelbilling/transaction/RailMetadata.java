package com.travelbilling.transaction;

import java.time.OffsetDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Stored in rail_legs (one row per leg; a change of train is two legs). */
public record RailMetadata(
    @NotEmpty List<@NotNull @Valid Leg> legs) implements TransactionMetadata {

    public record Leg(
        @NotBlank @Size(max = 255) String origin,
        @NotBlank @Size(max = 255) String destination,
        @NotNull OffsetDateTime departure,
        @NotNull OffsetDateTime arrival,
        @Size(max = 20) String trainNumber,
        // "class" is a Java keyword, so the field has another name but the JSON keeps "class".
        @JsonProperty("class") @Size(max = 50) String travelClass) {
    }
}
