package com.travelbilling.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/**
 * Brief §1 "the write must be atomic" (not one of the ten numbered tests): saved completely or not at all.
 * Header, tax lines, fee lines and type-specific rows share one DB transaction.
 */
class TransactionAtomicityTest extends IntegrationTest {

    /** The real repository, but we can make one method blow up on demand. */
    @MockitoSpyBean
    TransactionMetadataRepository metadataRepository;

    @Test
    void crashWhileSavingChildrenRollsBackEverything() {
        // By the time this runs, the header, tax lines and fee lines are already inserted.
        doThrow(new IllegalStateException("simulated crash"))
            .when(metadataRepository).insert(anyLong(), any());

        postTransaction(fixture("flight"))
            .statusCode(500)
            .body("error", equalTo("internal_error"));

        assertThat(countRows("transactions")).isZero();
        assertThat(countRows("transaction_tax_lines")).isZero();
        assertThat(countRows("transaction_fee_lines")).isZero();
        assertThat(countRows("flight_details")).isZero();

        // The external_id wasn't left "claimed" by the failed attempt: a retry succeeds.
        reset(metadataRepository);
        createTransaction(fixture("flight"));
        assertThat(countRows("transactions")).isEqualTo(1);
    }

    @Test
    void invalidMetadataRollsBackTheAlreadyInsertedHeader() {
        // A NEW external_id with no coupons. The header is inserted first (identity check),
        // then metadata validation fails and the header must disappear again.
        ObjectNode flight = fixture("flight");
        flight.put("external_id", "flight-without-coupons");
        ((ObjectNode) flight.get("metadata")).putArray("coupons");

        postTransaction(flight)
            .statusCode(400)
            .body("error", equalTo("validation_failed"))
            .body("details", hasItem(startsWith("metadata.coupons")));

        assertThat(countRows("transactions")).isZero();
        assertThat(countRows("transaction_tax_lines")).isZero();
    }
}
