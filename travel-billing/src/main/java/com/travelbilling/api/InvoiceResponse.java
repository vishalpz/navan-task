package com.travelbilling.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.travelbilling.common.PublicIds;
import com.travelbilling.invoice.Invoice;
import com.travelbilling.invoice.InvoiceLine;
import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.TaxLine;
import com.travelbilling.transaction.TransactionType;

/** JSON shape of an invoice: totals, lines (each with its frozen metadata), and both rollups. */
public record InvoiceResponse(
    String id,
    String currency,
    Map<String, BigDecimal> totals,
    List<Line> lines,
    List<TaxLine> taxRollup,
    List<FeeLine> feeRollup,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {

    /**
     * @JsonRawValue: these strings already ARE JSON (the snapshot from the JSONB columns),
     * so they're written into the response as-is, as objects/arrays, not as quoted strings.
     */
    public record Line(
        String transactionId,
        TransactionType type,
        String feeKind,
        String externalId,
        String currency,
        OffsetDateTime occurredAt,
        BigDecimal total,
        @JsonRawValue String taxLines,
        @JsonRawValue String feeLines,
        @JsonRawValue String metadata) {

        static Line from(InvoiceLine l) {
            return new Line(PublicIds.transaction(l.transactionId()), l.type(), l.feeKind(), l.externalId(),
                l.currency(), l.occurredAt(), l.total(), l.taxLinesJson(), l.feeLinesJson(), l.metadataJson());
        }
    }

    public static InvoiceResponse from(Invoice invoice) {
        return new InvoiceResponse(
            PublicIds.invoice(invoice.id()),
            invoice.currency(),
            invoice.totals(),
            invoice.lines().stream().map(Line::from).toList(),
            invoice.taxRollup(),
            invoice.feeRollup(),
            invoice.createdAt(),
            invoice.updatedAt());
    }
}
