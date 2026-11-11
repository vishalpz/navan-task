package com.travelbilling.transaction;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelbilling.IntegrationTest;

/**
 * Brief tests #2 (duplicate external_id) and #6 (concurrent duplicate create: simultaneousDuplicatesCreateExactlyOneRow).
 * Two POSTs with the same external_id yield one stored transaction.
 * The second gets 409 with the id of the first.
 */
class DuplicateTransactionTest extends IntegrationTest {

    @Test
    void fixtureDuplicateFlightGets409AndLeavesTheOriginalUntouched() {
        String originalId = createTransaction(fixture("flight"));

        // duplicate_flight has coupons: []. If we validated metadata first this would be a 400.
        postTransaction(fixture("duplicate_flight"))
            .statusCode(409)
            .body("error", equalTo("duplicate_external_id"))
            .body("existing_id", equalTo(originalId));

        assertThat(countRows("transactions")).isEqualTo(1);
        assertThat(countRows("flight_coupons")).isEqualTo(1);   // original's coupon, not replaced by []
        given().when().get("/transactions/" + originalId)
            .then().statusCode(200)
            .body("metadata.coupons", hasSize(1));
    }

    @Test
    void identityIsTheExternalIdAloneNotTheBody() {
        String originalId = createTransaction(fixture("flight"));

        // A completely different body (a hotel!) that reuses the flight's external_id.
        ObjectNode hotel = fixture("hotel");
        hotel.put("external_id", "flight-TXL-LHR-2026-03-11");

        postTransaction(hotel)
            .statusCode(409)
            .body("existing_id", equalTo(originalId));
        assertThat(countRows("transactions")).isEqualTo(1);
        assertThat(countRows("hotel_details")).isZero();
    }

    /**
     * Fires the same POST from several threads at once. A Java "check then insert" would let
     * two through; the DB unique constraint + ON CONFLICT lets exactly one through.
     */
    @Test
    void simultaneousDuplicatesCreateExactlyOneRow() throws Exception {
        int callers = 8;
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/transactions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json(fixture("flight"))))
            .build();

        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<HttpResponse<String>> responses = new ArrayList<>();
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                }));
            }
            startTogether.countDown();
            for (Future<HttpResponse<String>> f : futures) {
                responses.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<HttpResponse<String>> created = responses.stream().filter(r -> r.statusCode() == 201).toList();
        List<HttpResponse<String>> conflicts = responses.stream().filter(r -> r.statusCode() == 409).toList();
        assertThat(created).hasSize(1);
        assertThat(conflicts).hasSize(callers - 1);

        String createdId = JSON.readTree(created.get(0).body()).get("id").asText();
        for (HttpResponse<String> conflict : conflicts) {
            assertThat(JSON.readTree(conflict.body()).get("existing_id").asText()).isEqualTo(createdId);
        }
        assertThat(countRows("transactions")).isEqualTo(1);
        assertThat(countRows("flight_coupons")).isEqualTo(1);
    }
}
