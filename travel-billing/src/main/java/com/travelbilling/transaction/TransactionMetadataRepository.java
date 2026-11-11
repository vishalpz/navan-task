package com.travelbilling.transaction;

import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.travelbilling.common.Jdbc;
import com.travelbilling.transaction.FlightMetadata.Coupon;
import com.travelbilling.transaction.HotelMetadata.Address;
import com.travelbilling.transaction.HotelMetadata.Extra;
import com.travelbilling.transaction.RailMetadata.Leg;

/** SQL for the type-specific tables: flight_*, hotel_*, rail_legs, navan_fee_details. */
@Repository
public class TransactionMetadataRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TransactionMetadataRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ writes

    public void insert(long transactionId, TransactionMetadata metadata) {
        // Pattern-matching switch over a sealed interface: the compiler checks all four types are handled.
        switch (metadata) {
            case FlightMetadata flight -> insertFlight(transactionId, flight);
            case HotelMetadata hotel -> insertHotel(transactionId, hotel);
            case RailMetadata rail -> insertRail(transactionId, rail);
            case NavanFeeMetadata fee -> insertNavanFee(transactionId, fee);
        }
    }

    /** PATCH of metadata: remove this type's rows, then {@link #insert} the new ones. */
    public void delete(long transactionId, TransactionType type) {
        List<String> tables = switch (type) {
            case FLIGHT -> List.of("flight_coupons", "flight_details");
            case HOTEL -> List.of("hotel_extras", "hotel_details");
            case RAIL -> List.of("rail_legs");
            case NAVAN_FEE -> List.of("navan_fee_details");
        };
        for (String table : tables) {   // fixed names from the switch above, never user input
            jdbc.update("DELETE FROM " + table + " WHERE transaction_id = :id", Map.of("id", transactionId));
        }
    }

    private void insertFlight(long id, FlightMetadata flight) {
        jdbc.update("""
                INSERT INTO flight_details (transaction_id, origin, destination, fare)
                VALUES (:transactionId, :origin, :destination, :fare)
                """,
            new MapSqlParameterSource()
                .addValue("transactionId", id)
                .addValue("origin", flight.origin())
                .addValue("destination", flight.destination())
                .addValue("fare", flight.fare()));

        jdbc.batchUpdate("""
                INSERT INTO flight_coupons (transaction_id, position, flight_number, carrier,
                                            from_location, to_location, departure, arrival)
                VALUES (:transactionId, :position, :flightNumber, :carrier, :from, :to, :departure, :arrival)
                """,
            Jdbc.positioned("transactionId", id, flight.coupons(), (params, c) -> params
                .addValue("flightNumber", c.flightNumber())
                .addValue("carrier", c.carrier(), Types.VARCHAR)
                .addValue("from", c.from())
                .addValue("to", c.to())
                .addValue("departure", c.departure())
                .addValue("arrival", c.arrival())));
    }

    private void insertHotel(long id, HotelMetadata hotel) {
        jdbc.update("""
                INSERT INTO hotel_details (transaction_id, name, street, city, country,
                                           check_in, check_out, nights, room_rate_per_night)
                VALUES (:transactionId, :name, :street, :city, :country,
                        :checkIn, :checkOut, :nights, :roomRatePerNight)
                """,
            new MapSqlParameterSource()
                .addValue("transactionId", id)
                .addValue("name", hotel.name())
                .addValue("street", hotel.address().street())
                .addValue("city", hotel.address().city())
                .addValue("country", hotel.address().country())
                .addValue("checkIn", hotel.checkIn())
                .addValue("checkOut", hotel.checkOut())
                .addValue("nights", hotel.nights())
                .addValue("roomRatePerNight", hotel.roomRatePerNight()));

        jdbc.batchUpdate("""
                INSERT INTO hotel_extras (transaction_id, position, name, amount)
                VALUES (:transactionId, :position, :name, :amount)
                """,
            Jdbc.positioned("transactionId", id, hotel.extras(), (params, e) -> params
                .addValue("name", e.name())
                .addValue("amount", e.amount())));
    }

    private void insertRail(long id, RailMetadata rail) {
        jdbc.batchUpdate("""
                INSERT INTO rail_legs (transaction_id, position, origin, destination,
                                       departure, arrival, train_number, travel_class)
                VALUES (:transactionId, :position, :origin, :destination,
                        :departure, :arrival, :trainNumber, :travelClass)
                """,
            Jdbc.positioned("transactionId", id, rail.legs(), (params, leg) -> params
                .addValue("origin", leg.origin())
                .addValue("destination", leg.destination())
                .addValue("departure", leg.departure())
                .addValue("arrival", leg.arrival())
                .addValue("trainNumber", leg.trainNumber(), Types.VARCHAR)
                .addValue("travelClass", leg.travelClass(), Types.VARCHAR)));
    }

    private void insertNavanFee(long id, NavanFeeMetadata fee) {
        jdbc.update("""
                INSERT INTO navan_fee_details (transaction_id, fee_kind, description, related_external_id)
                VALUES (:transactionId, :feeKind, :description, :relatedExternalId)
                """,
            new MapSqlParameterSource()
                .addValue("transactionId", id)
                .addValue("feeKind", fee.feeKind())
                .addValue("description", fee.description(), Types.VARCHAR)
                .addValue("relatedExternalId", fee.relatedExternalId(), Types.VARCHAR));
    }

    // ------------------------------------------------------------------ reads

    /** Loads metadata for many transactions of ONE type: 1–2 queries regardless of how many ids. */
    public Map<Long, TransactionMetadata> findByIds(TransactionType type, Collection<Long> ids) {
        return switch (type) {
            case FLIGHT -> findFlights(ids);
            case HOTEL -> findHotels(ids);
            case RAIL -> findRail(ids);
            case NAVAN_FEE -> findNavanFees(ids);
        };
    }

    private Map<Long, TransactionMetadata> findFlights(Collection<Long> ids) {
        Map<String, Object> params = Map.of("ids", ids);
        Map<Long, List<Coupon>> coupons = Jdbc.groupBy(jdbc, """
                SELECT transaction_id, carrier, flight_number, from_location, to_location, departure, arrival
                FROM flight_coupons
                WHERE transaction_id IN (:ids)
                ORDER BY transaction_id, position
                """,
            params, "transaction_id",
            (rs, rowNum) -> new Coupon(
                rs.getString("carrier"),
                rs.getString("flight_number"),
                rs.getString("from_location"),
                rs.getString("to_location"),
                rs.getObject("departure", OffsetDateTime.class),
                rs.getObject("arrival", OffsetDateTime.class)));

        Map<Long, TransactionMetadata> result = new HashMap<>();
        jdbc.query("""
                SELECT transaction_id, origin, destination, fare
                FROM flight_details
                WHERE transaction_id IN (:ids)
                """,
            params,
            (RowCallbackHandler) rs -> {
                long id = rs.getLong("transaction_id");
                result.put(id, new FlightMetadata(
                    rs.getString("origin"),
                    rs.getString("destination"),
                    rs.getBigDecimal("fare"),
                    coupons.getOrDefault(id, List.of())));
            });
        return result;
    }

    private Map<Long, TransactionMetadata> findHotels(Collection<Long> ids) {
        Map<String, Object> params = Map.of("ids", ids);
        Map<Long, List<Extra>> extras = Jdbc.groupBy(jdbc, """
                SELECT transaction_id, name, amount
                FROM hotel_extras
                WHERE transaction_id IN (:ids)
                ORDER BY transaction_id, position
                """,
            params, "transaction_id",
            (rs, rowNum) -> new Extra(rs.getString("name"), rs.getBigDecimal("amount")));

        Map<Long, TransactionMetadata> result = new HashMap<>();
        jdbc.query("""
                SELECT transaction_id, name, street, city, country,
                       check_in, check_out, nights, room_rate_per_night
                FROM hotel_details
                WHERE transaction_id IN (:ids)
                """,
            params,
            (RowCallbackHandler) rs -> {
                long id = rs.getLong("transaction_id");
                result.put(id, new HotelMetadata(
                    rs.getString("name"),
                    new Address(rs.getString("street"), rs.getString("city"), rs.getString("country")),
                    rs.getObject("check_in", LocalDate.class),
                    rs.getObject("check_out", LocalDate.class),
                    rs.getInt("nights"),
                    rs.getBigDecimal("room_rate_per_night"),
                    extras.getOrDefault(id, List.of())));
            });
        return result;
    }

    private Map<Long, TransactionMetadata> findRail(Collection<Long> ids) {
        Map<Long, List<Leg>> legs = Jdbc.groupBy(jdbc, """
                SELECT transaction_id, origin, destination, departure, arrival, train_number, travel_class
                FROM rail_legs
                WHERE transaction_id IN (:ids)
                ORDER BY transaction_id, position
                """,
            Map.of("ids", ids), "transaction_id",
            (rs, rowNum) -> new Leg(
                rs.getString("origin"),
                rs.getString("destination"),
                rs.getObject("departure", OffsetDateTime.class),
                rs.getObject("arrival", OffsetDateTime.class),
                rs.getString("train_number"),
                rs.getString("travel_class")));

        // Rail has no header-details table: its metadata is just the legs.
        Map<Long, TransactionMetadata> result = new HashMap<>();
        for (Long id : ids) {
            result.put(id, new RailMetadata(legs.getOrDefault(id, List.of())));
        }
        return result;
    }

    private Map<Long, TransactionMetadata> findNavanFees(Collection<Long> ids) {
        Map<Long, TransactionMetadata> result = new HashMap<>();
        jdbc.query("""
                SELECT transaction_id, fee_kind, description, related_external_id
                FROM navan_fee_details
                WHERE transaction_id IN (:ids)
                """,
            Map.of("ids", ids),
            (RowCallbackHandler) rs -> result.put(rs.getLong("transaction_id"), new NavanFeeMetadata(
                rs.getString("fee_kind"),
                rs.getString("description"),
                rs.getString("related_external_id"))));
        return result;
    }
}
