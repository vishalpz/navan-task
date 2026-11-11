package com.travelbilling.invoice;

import java.math.BigDecimal;
import java.util.List;

import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.TaxLine;

/**
 * Everything an invoice will store, worked out by {@link InvoiceAssembler} but not yet saved
 * (so it has no id). Rollup entries reuse the TaxLine/FeeLine shapes: name, (rate), amount.
 */
public record InvoiceDraft(
    String currency,
    BigDecimal grandTotal,
    List<InvoiceLine> lines,
    List<TaxLine> taxRollup,
    List<FeeLine> feeRollup) {
}
