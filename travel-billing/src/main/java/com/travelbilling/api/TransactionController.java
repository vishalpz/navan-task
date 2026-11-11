package com.travelbilling.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.travelbilling.common.NotFoundException;
import com.travelbilling.common.Page;
import com.travelbilling.common.PublicIds;
import com.travelbilling.common.RequestValidationException;
import com.travelbilling.transaction.CreateTransactionRequest;
import com.travelbilling.transaction.PatchTransactionRequest;
import com.travelbilling.transaction.TransactionService;

import jakarta.validation.Valid;

/** HTTP only: parse, validate the envelope, call the service, shape the response. No business rules here. */
@RestController
@RequestMapping("/transactions")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request) {
        TransactionResponse body = TransactionResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/transactions/" + body.id())).body(body);
    }

    /** GET /transactions?limit=50&cursor=txn_50. Default 50, max 100 (more → 400). */
    @GetMapping
    public PageResponse<TransactionResponse> list(
            @RequestParam(defaultValue = "" + Page.DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        long afterId = cursor == null ? 0 : PublicIds.parseTransaction(cursor)
            .orElseThrow(() -> new RequestValidationException(List.of("cursor: not a valid transaction cursor")));
        Page<TransactionResponse> page = service.list(afterId, limit).map(TransactionResponse::from);
        return new PageResponse<>(page.items(), limit,
            page.nextAfterId() == null ? null : PublicIds.transaction(page.nextAfterId()));
    }

    @GetMapping("/{id}")
    public TransactionResponse get(@PathVariable String id) {
        return TransactionResponse.from(service.get(parseId(id)));
    }

    @PatchMapping("/{id}")
    public TransactionResponse update(@PathVariable String id, @Valid @RequestBody PatchTransactionRequest patch) {
        return TransactionResponse.from(service.update(parseId(id), patch));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(parseId(id));
        return ResponseEntity.noContent().build();
    }

    /** A malformed id ("abc", "inv_1") can't exist, so it's a 404 like any other unknown id. */
    private static long parseId(String id) {
        return PublicIds.parseTransaction(id)
            .orElseThrow(() -> new NotFoundException("transaction " + id + " not found"));
    }
}
