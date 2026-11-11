package com.travelbilling.transaction;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.travelbilling.common.Jdbc;

/**
 * SQL for the shared parts of a transaction: the header row, tax lines and fee lines.
 * Type-specific rows (coupons, legs, ...) live in {@link TransactionMetadataRepository}.
 */
@Repository
public class TransactionRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionMetadataRepository metadataRepository;

    public TransactionRepository(NamedParameterJdbcTemplate jdbc, TransactionMetadataRepository metadataRepository) {
        this.jdbc = jdbc;
        this.metadataRepository = metadataRepository;
    }

    // ------------------------------------------------------------------ writes

    /**
     * Inserts the header, or does nothing if the external_id is already taken.
     *
     * The UNIQUE constraint decides, not a "SELECT first, then INSERT" in Java, so two
     * simultaneous requests can never both get in. If another transaction is inserting the same
     * external_id right now, Postgres makes this statement wait until that one commits or rolls back.
     *
     * @return the new id, or empty if the external_id already exists
     */
    public Optional<Long> insertIfAbsent(CreateTransactionRequest request) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO transactions (type, external_id, currency, occurred_at, total)
                VALUES (:type, :externalId, :currency, :occurredAt, :total)
                ON CONFLICT (external_id) DO NOTHING
                RETURNING id
                """,
            new MapSqlParameterSource()
                .addValue("type", request.type().code())
                .addValue("externalId", request.externalId())
                .addValue("currency", request.currency())
                .addValue("occurredAt", request.occurredAt())
                .addValue("total", request.total()),
            Long.class);
        return ids.stream().findFirst();
    }

    public Optional<Long> findIdByExternalId(String externalId) {
        return jdbc.queryForList(
                "SELECT id FROM transactions WHERE external_id = :externalId",
                Map.of("externalId", externalId), Long.class)
            .stream().findFirst();
    }

    public void insertTaxLines(long transactionId, List<TaxLine> taxLines) {
        jdbc.batchUpdate("""
                INSERT INTO transaction_tax_lines (transaction_id, position, name, rate, amount)
                VALUES (:transactionId, :position, :name, :rate, :amount)
                """,
            Jdbc.positioned("transactionId", transactionId, taxLines, (params, line) -> params
                .addValue("name", line.name())
                .addValue("rate", line.rate(), Types.NUMERIC)   // may be null
                .addValue("amount", line.amount())));
    }

    public void insertFeeLines(long transactionId, List<FeeLine> feeLines) {
        jdbc.batchUpdate("""
                INSERT INTO transaction_fee_lines (transaction_id, position, name, amount)
                VALUES (:transactionId, :position, :name, :amount)
                """,
            Jdbc.positioned("transactionId", transactionId, feeLines, (params, line) -> params
                .addValue("name", line.name())
                .addValue("amount", line.amount())));
    }

    /** PATCH: header fields that may change. updated_at is set by the database clock. */
    public void updateHeader(long transactionId, OffsetDateTime occurredAt, BigDecimal total) {
        jdbc.update("""
                UPDATE transactions
                SET occurred_at = :occurredAt, total = :total, updated_at = now()
                WHERE id = :id
                """,
            new MapSqlParameterSource()
                .addValue("id", transactionId)
                .addValue("occurredAt", occurredAt)
                .addValue("total", total));
    }

    /** PATCH semantics for lists: the new list replaces the old one completely. */
    public void replaceTaxLines(long transactionId, List<TaxLine> taxLines) {
        jdbc.update("DELETE FROM transaction_tax_lines WHERE transaction_id = :id", Map.of("id", transactionId));
        insertTaxLines(transactionId, taxLines);
    }

    public void replaceFeeLines(long transactionId, List<FeeLine> feeLines) {
        jdbc.update("DELETE FROM transaction_fee_lines WHERE transaction_id = :id", Map.of("id", transactionId));
        insertFeeLines(transactionId, feeLines);
    }

    /** Deletes the transaction; ON DELETE CASCADE removes all its child rows. */
    public void delete(long transactionId) {
        jdbc.update("DELETE FROM transactions WHERE id = :id", Map.of("id", transactionId));
    }

    /**
     * Which invoices contain this transaction? Checked before DELETE.
     * Served by the index ix_invoice_lines_transaction_id. This only checks for the transaction's
     * presence; it never reads any amounts.
     */
    public List<Long> findInvoiceIdsContaining(long transactionId) {
        return jdbc.queryForList("""
                SELECT DISTINCT invoice_id FROM invoice_lines
                WHERE transaction_id = :id
                ORDER BY invoice_id
                """,
            Map.of("id", transactionId), Long.class);
    }

    // ------------------------------------------------------------------ locks

    /**
     * Row-locks these transactions until the current DB transaction ends. Anyone else who tries to
     * lock or change them waits. ORDER BY id means locks are always taken in the same order,
     * so two requests locking overlapping sets can't deadlock (each waiting for the other's row).
     *
     * @return the ids that exist (missing ids are simply absent)
     */
    public List<Long> lockForUpdate(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT id FROM transactions
                WHERE id IN (:ids)
                ORDER BY id
                FOR UPDATE
                """,
            Map.of("ids", ids), Long.class);
    }

    // ------------------------------------------------------------------ reads

    /**
     * Keyset paging: the next `count` ids after `afterId`. Uses the primary-key index to jump
     * straight to the start point, so page 1,000 is as cheap as page 1 (unlike OFFSET).
     * Always bounded by LIMIT; the whole table is never read.
     */
    public List<Long> findIdsAfter(long afterId, int count) {
        return jdbc.queryForList("""
                SELECT id FROM transactions
                WHERE id > :afterId
                ORDER BY id
                LIMIT :count
                """,
            Map.of("afterId", afterId, "count", count), Long.class);
    }

    /**
     * Loads full transactions for a set of ids with a fixed number of queries
     * (headers, tax lines, fee lines, then 1–2 per type present), however many ids there are.
     * Missing ids are simply absent from the result. Result is ordered by id.
     */
    public List<Transaction> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = Map.of("ids", ids);

        List<Header> headers = jdbc.query("""
                SELECT id, type, external_id, currency, occurred_at, total, created_at, updated_at
                FROM transactions
                WHERE id IN (:ids)
                ORDER BY id
                """,
            params,
            (rs, rowNum) -> new Header(
                rs.getLong("id"),
                TransactionType.fromCode(rs.getString("type")),
                rs.getString("external_id"),
                rs.getString("currency"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getBigDecimal("total"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class)));
        if (headers.isEmpty()) {
            return List.of();
        }

        Map<Long, List<TaxLine>> taxLines = Jdbc.groupBy(jdbc, """
                SELECT transaction_id, name, rate, amount
                FROM transaction_tax_lines
                WHERE transaction_id IN (:ids)
                ORDER BY transaction_id, position
                """,
            params, "transaction_id",
            (rs, rowNum) -> new TaxLine(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")));

        Map<Long, List<FeeLine>> feeLines = Jdbc.groupBy(jdbc, """
                SELECT transaction_id, name, amount
                FROM transaction_fee_lines
                WHERE transaction_id IN (:ids)
                ORDER BY transaction_id, position
                """,
            params, "transaction_id",
            (rs, rowNum) -> new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")));

        // Only query the detail tables for types that are actually in this batch.
        Map<TransactionType, List<Long>> idsByType = headers.stream()
            .collect(groupingBy(Header::type, mapping(Header::id, toList())));
        Map<Long, TransactionMetadata> metadata = new HashMap<>();
        idsByType.forEach((type, typeIds) -> metadata.putAll(metadataRepository.findByIds(type, typeIds)));

        return headers.stream()
            .map(h -> new Transaction(
                h.id(), h.type(), h.externalId(), h.currency(), h.occurredAt(), h.total(),
                taxLines.getOrDefault(h.id(), List.of()),
                feeLines.getOrDefault(h.id(), List.of()),
                metadata.get(h.id()),
                h.createdAt(), h.updatedAt()))
            .toList();
    }

    /** The transactions row on its own, before children are attached. */
    private record Header(long id, TransactionType type, String externalId, String currency,
                          OffsetDateTime occurredAt, BigDecimal total,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }
}
