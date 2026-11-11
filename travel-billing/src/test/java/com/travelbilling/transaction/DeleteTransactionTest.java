package com.travelbilling.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.travelbilling.IntegrationTest;

/** Test #8: a transaction can be deleted only while it's on no invoice. */
class DeleteTransactionTest extends IntegrationTest {

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void uninvoicedTransactionIsDeletedWithAllItsChildren() {
        String id = createTransaction(fixture("flight"));

        delete("/transactions/" + id).statusCode(204);

        delete("/transactions/" + id).statusCode(404);
        assertThat(countRows("transactions")).isZero();
        assertThat(countRows("flight_coupons")).isZero();
        assertThat(countRows("transaction_tax_lines")).isZero();
    }

    @Test
    void invoicedTransactionIs409UntilItsInvoiceIsDeleted() {
        String id = createTransaction(fixture("flight"));
        String invoiceId = postInvoice(Map.of("transaction_id", id)).statusCode(201).extract().path("id");

        delete("/transactions/" + id)
            .statusCode(409)
            .body("error", equalTo("transaction_invoiced"))
            .body("message", containsString(invoiceId));
        assertThat(countRows("transactions")).isEqualTo(1);

        delete("/invoices/" + invoiceId).statusCode(204);
        delete("/transactions/" + id).statusCode(204);
    }

    /**
     * The row lock in action. The test plays an invoice-create that has locked the transaction and
     * is mid-copy. A DELETE arriving now must WAIT. Once the invoice commits, the DELETE must see
     * it and answer 409. Without the lock the DELETE could check "no invoices yet" and go ahead.
     */
    @Test
    void deleteWaitsForAnInvoiceInProgressAndThenRefuses() throws Exception {
        String id = createTransaction(fixture("flight"));
        long txnId = dbId(id);
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest deleteRequest = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions/" + id))
            .DELETE().build();
        AtomicReference<CompletableFuture<HttpResponse<String>>> pendingDelete = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM transactions WHERE id = ? FOR UPDATE", Long.class, txnId);

            pendingDelete.set(client.sendAsync(deleteRequest, HttpResponse.BodyHandlers.ofString()));
            pause(500);
            assertThat(pendingDelete.get()).as("DELETE should be blocked by the row lock").isNotDone();

            Long invoiceId = jdbc.queryForObject(
                "INSERT INTO invoices (currency, grand_total) VALUES ('EUR', 220) RETURNING id", Long.class);
            jdbc.update("""
                    INSERT INTO invoice_lines (invoice_id, transaction_id, position, type, external_id,
                                               currency, occurred_at, total, tax_lines, fee_lines, metadata)
                    VALUES (?, ?, 0, 'flight', 'flight-TXL-LHR-2026-03-11', 'EUR', now(), 220, '[]', '[]', '{}')""",
                invoiceId, txnId);
        });   // commit → lock released → DELETE continues

        assertThat(pendingDelete.get().get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
        assertThat(countRows("transactions")).isEqualTo(1);
    }

    @Test
    void unknownTransactionIs404() {
        delete("/transactions/txn_999").statusCode(404);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
