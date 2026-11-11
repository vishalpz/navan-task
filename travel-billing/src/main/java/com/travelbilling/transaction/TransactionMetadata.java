package com.travelbilling.transaction;

/**
 * The type-specific part of a transaction. "sealed" = only these four exist,
 * so a switch over it must handle all four or it won't compile.
 */
public sealed interface TransactionMetadata
    permits FlightMetadata, HotelMetadata, RailMetadata, NavanFeeMetadata {
}
