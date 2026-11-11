package com.travelbilling.invoice;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.travelbilling.IntegrationTest;

/** GET /invoices follows the same paging rules as GET /transactions. */
class ListInvoicesTest extends IntegrationTest {

    @Test
    void invoicesArePagedWithACursor() {
        Map<String, String> trip = createTrip();
        List<String> invoiceIds = new ArrayList<>();
        for (String txnId : trip.values()) {
            invoiceIds.add(postInvoice(Map.of("transaction_id", txnId)).statusCode(201).extract().path("id"));
        }

        given().queryParam("limit", 2).when().get("/invoices")
            .then().statusCode(200)
            .body("data", hasSize(2))
            .body("data[0].id", equalTo(invoiceIds.get(0)))
            .body("data[0].totals.flight", money("220"))
            .body("data[1].lines[0].metadata.name", equalTo("Hotel Mitte London"))
            .body("next_cursor", equalTo(invoiceIds.get(1)));

        given().queryParam("limit", 2).queryParam("cursor", invoiceIds.get(3)).when().get("/invoices")
            .then().statusCode(200)
            .body("data", hasSize(1))
            .body("data[0].id", equalTo(invoiceIds.get(4)))
            .body("$", not(hasKey("next_cursor")));
    }

    @Test
    void limitAbove100IsRejected() {
        given().when().get("/invoices?limit=1000").then().statusCode(400);
    }

    @Test
    void emptyListIsAnEmptyPage() {
        given().when().get("/invoices")
            .then().statusCode(200)
            .body("data", hasSize(0))
            .body("limit", equalTo(50));
    }
}
