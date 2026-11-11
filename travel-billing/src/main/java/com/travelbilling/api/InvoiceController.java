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
import com.travelbilling.invoice.CreateInvoiceRequest;
import com.travelbilling.invoice.InvoiceService;

@RestController
@RequestMapping("/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> create(@RequestBody CreateInvoiceRequest request) {
        InvoiceResponse body = InvoiceResponse.from(service.create(request.requestedIds()));
        return ResponseEntity.created(URI.create("/invoices/" + body.id())).body(body);
    }

    /** GET /invoices?limit=50&cursor=inv_50. Default 50, max 100 (more → 400). */
    @GetMapping
    public PageResponse<InvoiceResponse> list(
            @RequestParam(defaultValue = "" + Page.DEFAULT_LIMIT) int limit,
            @RequestParam(required = false) String cursor) {
        long afterId = cursor == null ? 0 : PublicIds.parseInvoice(cursor)
            .orElseThrow(() -> new RequestValidationException(List.of("cursor: not a valid invoice cursor")));
        Page<InvoiceResponse> page = service.list(afterId, limit).map(InvoiceResponse::from);
        return new PageResponse<>(page.items(), limit,
            page.nextAfterId() == null ? null : PublicIds.invoice(page.nextAfterId()));
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable String id) {
        return InvoiceResponse.from(service.get(parseId(id)));
    }

    /** Same body as POST. Replaces the invoice's contents with a fresh snapshot; the id stays. */
    @PatchMapping("/{id}")
    public InvoiceResponse update(@PathVariable String id, @RequestBody CreateInvoiceRequest request) {
        return InvoiceResponse.from(service.update(parseId(id), request.requestedIds()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(parseId(id));
        return ResponseEntity.noContent().build();
    }

    private static long parseId(String id) {
        return PublicIds.parseInvoice(id)
            .orElseThrow(() -> new NotFoundException("invoice " + id + " not found"));
    }
}
