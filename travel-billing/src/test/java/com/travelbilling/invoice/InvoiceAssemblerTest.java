package com.travelbilling.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelbilling.common.ConflictException;
import com.travelbilling.transaction.FeeLine;
import com.travelbilling.transaction.FlightMetadata;
import com.travelbilling.transaction.NavanFeeMetadata;
import com.travelbilling.transaction.RailMetadata;
import com.travelbilling.transaction.TaxLine;
import com.travelbilling.transaction.Transaction;
import com.travelbilling.transaction.TransactionMetadata;
import com.travelbilling.transaction.TransactionType;

/** Pure unit test: no Spring, no database. The assembler is just a calculator. */
class InvoiceAssemblerTest {

    private final InvoiceAssembler assembler = new InvoiceAssembler(new ObjectMapper().findAndRegisterModules());

    @Test
    void totalsSplitNavanFeesByKindAndAddUpExactly() {
        InvoiceDraft draft = assembler.assemble(List.of(
            txn(1, TransactionType.FLIGHT, "EUR", "220.00", List.of(), List.of(), flight()),
            txn(2, TransactionType.NAVAN_FEE, "EUR", "25.00", List.of(), List.of(), fee("trip_fee")),
            txn(3, TransactionType.NAVAN_FEE, "EUR", "0.10", List.of(), List.of(), fee("agent_call_fee")),
            txn(4, TransactionType.NAVAN_FEE, "EUR", "0.20", List.of(), List.of(), fee("agent_call_fee"))));

        assertThat(draft.grandTotal()).isEqualByComparingTo("245.30");
        var totals = InvoiceAssembler.totals(draft.lines(), draft.grandTotal());
        assertThat(totals).containsOnlyKeys("flight", "trip_fee", "agent_call_fee", "grand_total");
        assertThat(totals.get("agent_call_fee")).isEqualByComparingTo("0.30");   // not 0.30000000000000004
    }

    @Test
    void taxesGroupByNameAndRateTreatingEqualRatesAsEqual() {
        InvoiceDraft draft = assembler.assemble(List.of(
            txn(1, TransactionType.RAIL, "EUR", "10", List.of(
                new TaxLine("VAT", new BigDecimal("0.07"), new BigDecimal("1.00")),
                new TaxLine("Airport tax", null, new BigDecimal("2.00"))), List.of(), rail()),
            txn(2, TransactionType.RAIL, "EUR", "10", List.of(
                new TaxLine("VAT", new BigDecimal("0.070000"), new BigDecimal("1.50")),   // same rate, more zeros
                new TaxLine("VAT", new BigDecimal("0.20"), new BigDecimal("3.00")),       // different rate
                new TaxLine("Airport tax", null, new BigDecimal("2.00"))), List.of(), rail())));

        assertThat(draft.taxRollup()).hasSize(3);
        assertThat(draft.taxRollup().get(0).name()).isEqualTo("VAT");
        assertThat(draft.taxRollup().get(0).amount()).isEqualByComparingTo("2.50");
        assertThat(draft.taxRollup().get(1).name()).isEqualTo("Airport tax");
        assertThat(draft.taxRollup().get(1).rate()).isNull();
        assertThat(draft.taxRollup().get(1).amount()).isEqualByComparingTo("4.00");
        assertThat(draft.taxRollup().get(2).rate()).isEqualByComparingTo("0.20");
    }

    @Test
    void feeRollupOnlyContainsFeeLinesFromInsideTransactions() {
        InvoiceDraft draft = assembler.assemble(List.of(
            txn(1, TransactionType.FLIGHT, "EUR", "220", List.of(),
                List.of(new FeeLine("YQ", new BigDecimal("25"))), flight()),
            txn(2, TransactionType.NAVAN_FEE, "EUR", "25", List.of(), List.of(), fee("trip_fee"))));

        assertThat(draft.feeRollup()).extracting(FeeLine::name).containsExactly("YQ");
    }

    @Test
    void mixedCurrencyIsRejected() {
        assertThatThrownBy(() -> assembler.assemble(List.of(
                txn(1, TransactionType.FLIGHT, "EUR", "1", List.of(), List.of(), flight()),
                txn(2, TransactionType.FLIGHT, "USD", "1", List.of(), List.of(), flight()))))
            .isInstanceOf(ConflictException.class)
            .extracting(e -> ((ConflictException) e).error()).isEqualTo("mixed_currency");
    }

    // ------------------------------------------------------------------ helpers

    private static Transaction txn(long id, TransactionType type, String currency, String total,
                                   List<TaxLine> taxes, List<FeeLine> fees, TransactionMetadata metadata) {
        OffsetDateTime now = OffsetDateTime.parse("2026-03-11T06:40:00Z");
        return new Transaction(id, type, "ext-" + id, currency, now, new BigDecimal(total),
            taxes, fees, metadata, now, now);
    }

    private static FlightMetadata flight() {
        return new FlightMetadata("TXL", "LHR", new BigDecimal("180"), List.of());
    }

    private static RailMetadata rail() {
        return new RailMetadata(List.of());
    }

    private static NavanFeeMetadata fee(String kind) {
        return new NavanFeeMetadata(kind, null, null);
    }
}
