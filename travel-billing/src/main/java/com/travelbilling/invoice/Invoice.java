package com.travelbilling.invoice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.TaxLine;

/** A stored invoice, loaded entirely from the invoice tables. */
public record Invoice(
    long id,
    String currency,
    BigDecimal grandTotal,
    List<InvoiceLine> lines,
    List<TaxLine> taxRollup,
    List<FeeLine> feeRollup,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

    /** Per-type totals (from the frozen lines) + grand_total. */
    public Map<String, BigDecimal> totals() {
        return InvoiceAssembler.totals(lines, grandTotal);
    }
}
