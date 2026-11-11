package com.travelbilling.invoice;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelbilling.common.ConflictException;
import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.NavanFeeMetadata;
import com.travelbilling.transaction.TaxLine;
import com.travelbilling.transaction.Transaction;

/**
 * The invoice calculator. Pure logic: transactions in, invoice draft out.
 * No database, no HTTP, so it can be unit-tested with plain objects.
 *
 * Fixture rules it encodes:
 *  - totals are the transactions' gross totals (already include tax + fees), simply added up;
 *  - tax_rollup groups by (name, rate), and rate may be null (airport tax);
 *  - fee_rollup = only fee_lines found INSIDE transactions (YQ, booking fee). Navan fees are
 *    invoice lines of their own, and hotel extras are just metadata, so neither appears here.
 */
@Component
public class InvoiceAssembler {

    private final ObjectMapper objectMapper;

    public InvoiceAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @param transactions in the order they should appear on the invoice (at least one) */
    public InvoiceDraft assemble(List<Transaction> transactions) {
        String currency = transactions.get(0).currency();
        for (Transaction t : transactions) {
            if (!t.currency().equals(currency)) {
                throw new ConflictException("mixed_currency",
                    "all transactions on an invoice must share one currency, found " + currency + " and " + t.currency());
            }
        }

        List<InvoiceLine> lines = transactions.stream().map(this::snapshot).toList();
        BigDecimal grandTotal = lines.stream().map(InvoiceLine::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new InvoiceDraft(currency, grandTotal, lines, taxRollup(transactions), feeRollup(transactions));
    }

    /** Per-type totals in order of first appearance, then grand_total. Used when building and when reading. */
    public static Map<String, BigDecimal> totals(List<InvoiceLine> lines, BigDecimal grandTotal) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (InvoiceLine line : lines) {
            totals.merge(line.totalsKey(), line.total(), BigDecimal::add);
        }
        totals.put("grand_total", grandTotal);
        return totals;
    }

    // ------------------------------------------------------------------ internals

    /** Copy one transaction into an invoice line, nested parts frozen as JSON text. */
    private InvoiceLine snapshot(Transaction t) {
        String feeKind = t.metadata() instanceof NavanFeeMetadata fee ? fee.feeKind() : null;
        return new InvoiceLine(
            t.id(), t.type(), feeKind, t.externalId(), t.currency(), t.occurredAt(), t.total(),
            toJson(t.taxLines()), toJson(t.feeLines()), toJson(t.metadata()));
    }

    /**
     * Sum tax amounts per (name, rate). The rate is normalised (0.070000 → 0.07) so equal rates
     * group together no matter how many trailing zeros they carry. Order = first appearance.
     */
    private static List<TaxLine> taxRollup(List<Transaction> transactions) {
        Map<TaxKey, BigDecimal> sums = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            for (TaxLine tax : t.taxLines()) {
                BigDecimal rate = tax.rate() == null ? null : tax.rate().stripTrailingZeros();
                sums.merge(new TaxKey(tax.name(), rate), tax.amount(), BigDecimal::add);
            }
        }
        return sums.entrySet().stream()
            .map(e -> new TaxLine(e.getKey().name(), e.getKey().rate(), e.getValue()))
            .toList();
    }

    private static List<FeeLine> feeRollup(List<Transaction> transactions) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            for (FeeLine fee : t.feeLines()) {
                sums.merge(fee.name(), fee.amount(), BigDecimal::add);
            }
        }
        return sums.entrySet().stream()
            .map(e -> new FeeLine(e.getKey(), e.getValue()))
            .toList();
    }

    private record TaxKey(String name, BigDecimal rate) {
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not snapshot invoice line", e);
        }
    }
}
