package com.travelbilling.invoice;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.travelbilling.common.Jdbc;
import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.TaxLine;
import com.travelbilling.transaction.TransactionType;

/**
 * SQL for invoices. Note what's NOT here: no query joins to the transactions tables.
 * An invoice is read back purely from its own snapshot tables.
 */
@Repository
public class InvoiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public InvoiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ writes

    /** Saves header, lines and both rollups. The caller's @Transactional makes it all-or-nothing. */
    public long insert(InvoiceDraft draft) {
        Long id = jdbc.queryForObject("""
                INSERT INTO invoices (currency, grand_total)
                VALUES (:currency, :grandTotal)
                RETURNING id
                """,
            new MapSqlParameterSource()
                .addValue("currency", draft.currency())
                .addValue("grandTotal", draft.grandTotal()),
            Long.class);
        insertContents(id, draft);
        return id;
    }

    /**
     * PATCH: throw away the old snapshot (lines + rollups) and store a fresh one.
     * The header row (and so the invoice id) stays the same.
     */
    public void replaceContents(long invoiceId, InvoiceDraft draft) {
        Map<String, Object> id = Map.of("id", invoiceId);
        jdbc.update("DELETE FROM invoice_lines WHERE invoice_id = :id", id);
        jdbc.update("DELETE FROM invoice_tax_rollup WHERE invoice_id = :id", id);
        jdbc.update("DELETE FROM invoice_fee_rollup WHERE invoice_id = :id", id);
        jdbc.update("""
                UPDATE invoices
                SET currency = :currency, grand_total = :grandTotal, updated_at = now()
                WHERE id = :id
                """,
            new MapSqlParameterSource()
                .addValue("id", invoiceId)
                .addValue("currency", draft.currency())
                .addValue("grandTotal", draft.grandTotal()));
        insertContents(invoiceId, draft);
    }

    /** Locks the invoice row for the rest of the DB transaction. @return false if it doesn't exist */
    public boolean lockForUpdate(long invoiceId) {
        return !jdbc.queryForList("SELECT id FROM invoices WHERE id = :id FOR UPDATE",
            Map.of("id", invoiceId), Long.class).isEmpty();
    }

    /** Lines and rollups cascade away; the transactions themselves are untouched. @return rows deleted (0 or 1) */
    public int delete(long invoiceId) {
        return jdbc.update("DELETE FROM invoices WHERE id = :id", Map.of("id", invoiceId));
    }

    private void insertContents(long id, InvoiceDraft draft) {
        // CAST(... AS jsonb): we send JSON text, Postgres validates and stores it as JSONB.
        jdbc.batchUpdate("""
                INSERT INTO invoice_lines (invoice_id, transaction_id, position, type, fee_kind, external_id,
                                           currency, occurred_at, total, tax_lines, fee_lines, metadata)
                VALUES (:invoiceId, :transactionId, :position, :type, :feeKind, :externalId,
                        :currency, :occurredAt, :total,
                        CAST(:taxLines AS jsonb), CAST(:feeLines AS jsonb), CAST(:metadata AS jsonb))
                """,
            Jdbc.positioned("invoiceId", id, draft.lines(), (params, line) -> params
                .addValue("transactionId", line.transactionId())
                .addValue("type", line.type().code())
                .addValue("feeKind", line.feeKind(), Types.VARCHAR)
                .addValue("externalId", line.externalId())
                .addValue("currency", line.currency())
                .addValue("occurredAt", line.occurredAt())
                .addValue("total", line.total())
                .addValue("taxLines", line.taxLinesJson())
                .addValue("feeLines", line.feeLinesJson())
                .addValue("metadata", line.metadataJson())));

        jdbc.batchUpdate("""
                INSERT INTO invoice_tax_rollup (invoice_id, position, name, rate, amount)
                VALUES (:invoiceId, :position, :name, :rate, :amount)
                """,
            Jdbc.positioned("invoiceId", id, draft.taxRollup(), (params, tax) -> params
                .addValue("name", tax.name())
                .addValue("rate", tax.rate(), Types.NUMERIC)
                .addValue("amount", tax.amount())));

        jdbc.batchUpdate("""
                INSERT INTO invoice_fee_rollup (invoice_id, position, name, amount)
                VALUES (:invoiceId, :position, :name, :amount)
                """,
            Jdbc.positioned("invoiceId", id, draft.feeRollup(), (params, fee) -> params
                .addValue("name", fee.name())
                .addValue("amount", fee.amount())));
    }

    // ------------------------------------------------------------------ reads

    /** Keyset paging, same as TransactionRepository.findIdsAfter. */
    public List<Long> findIdsAfter(long afterId, int count) {
        return jdbc.queryForList("""
                SELECT id FROM invoices
                WHERE id > :afterId
                ORDER BY id
                LIMIT :count
                """,
            Map.of("afterId", afterId, "count", count), Long.class);
    }

    /** Loads full invoices with 4 queries, however many ids (ready for the Phase 5 list endpoint). */
    public List<Invoice> findByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Object> params = Map.of("ids", ids);

        Map<Long, List<InvoiceLine>> lines = Jdbc.groupBy(jdbc, """
                SELECT invoice_id, transaction_id, type, fee_kind, external_id, currency, occurred_at, total,
                       tax_lines::text AS tax_lines, fee_lines::text AS fee_lines, metadata::text AS metadata
                FROM invoice_lines
                WHERE invoice_id IN (:ids)
                ORDER BY invoice_id, position
                """,
            params, "invoice_id",
            (rs, rowNum) -> new InvoiceLine(
                rs.getLong("transaction_id"),
                TransactionType.fromCode(rs.getString("type")),
                rs.getString("fee_kind"),
                rs.getString("external_id"),
                rs.getString("currency"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getBigDecimal("total"),
                rs.getString("tax_lines"),
                rs.getString("fee_lines"),
                rs.getString("metadata")));

        Map<Long, List<TaxLine>> taxRollups = Jdbc.groupBy(jdbc, """
                SELECT invoice_id, name, rate, amount
                FROM invoice_tax_rollup
                WHERE invoice_id IN (:ids)
                ORDER BY invoice_id, position
                """,
            params, "invoice_id",
            (rs, rowNum) -> new TaxLine(rs.getString("name"), rs.getBigDecimal("rate"), rs.getBigDecimal("amount")));

        Map<Long, List<FeeLine>> feeRollups = Jdbc.groupBy(jdbc, """
                SELECT invoice_id, name, amount
                FROM invoice_fee_rollup
                WHERE invoice_id IN (:ids)
                ORDER BY invoice_id, position
                """,
            params, "invoice_id",
            (rs, rowNum) -> new FeeLine(rs.getString("name"), rs.getBigDecimal("amount")));

        return jdbc.query("""
                SELECT id, currency, grand_total, created_at, updated_at
                FROM invoices
                WHERE id IN (:ids)
                ORDER BY id
                """,
            params,
            (rs, rowNum) -> {
                long id = rs.getLong("id");
                BigDecimal grandTotal = rs.getBigDecimal("grand_total");
                return new Invoice(
                    id,
                    rs.getString("currency"),
                    grandTotal,
                    lines.getOrDefault(id, List.of()),
                    taxRollups.getOrDefault(id, List.of()),
                    feeRollups.getOrDefault(id, List.of()),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class));
            });
    }
}
