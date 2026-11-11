package com.travelbilling.invoice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.travelbilling.common.ConflictException;
import com.travelbilling.common.NotFoundException;
import com.travelbilling.common.Page;
import com.travelbilling.common.PublicIds;
import com.travelbilling.transaction.Transaction;
import com.travelbilling.transaction.TransactionRepository;

@Service
public class InvoiceService {

    private final TransactionRepository transactions;
    private final InvoiceRepository invoices;
    private final InvoiceAssembler assembler;

    public InvoiceService(TransactionRepository transactions, InvoiceRepository invoices, InvoiceAssembler assembler) {
        this.transactions = transactions;
        this.invoices = invoices;
        this.assembler = assembler;
    }

    /** @param publicIds transaction ids as the client sent them ("txn_1", ...), in invoice-line order */
    @Transactional
    public Invoice create(List<String> publicIds) {
        InvoiceDraft draft = buildDraft(publicIds);
        long invoiceId = invoices.insert(draft);
        return load(invoiceId);
    }

    /**
     * Rebuilds the invoice from scratch for the given transactions, using their CURRENT data:
     * old lines + rollups are deleted, a fresh snapshot is stored, all in one DB transaction.
     * If anything fails (404, mixed currency, ...) the old invoice is left exactly as it was.
     */
    @Transactional
    public Invoice update(long invoiceId, List<String> publicIds) {
        // Invoice first, then its transactions. Nothing locks in the opposite order, so no deadlock.
        if (!invoices.lockForUpdate(invoiceId)) {
            throw notFound(invoiceId);
        }
        InvoiceDraft draft = buildDraft(publicIds);
        invoices.replaceContents(invoiceId, draft);
        return load(invoiceId);
    }

    /** The transactions are untouched (and become deletable again if this was their only invoice). */
    @Transactional
    public void delete(long invoiceId) {
        if (invoices.delete(invoiceId) == 0) {
            throw notFound(invoiceId);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Invoice get(long id) {
        return load(id);
    }

    /** One page of invoices with id > afterId (0 = from the start). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Invoice> list(long afterId, int limit) {
        Page.checkLimit(limit);
        return Page.of(afterId, limit, invoices::findIdsAfter, invoices::findByIds, Invoice::id);
    }

    // ------------------------------------------------------------------ internals

    /** Shared by create and PATCH: check the ids, lock + load the transactions, compute the snapshot. */
    private InvoiceDraft buildDraft(List<String> publicIds) {
        // 1. Same id twice would bill it twice → 409.
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (String id : publicIds) {
            if (!seen.add(id)) {
                repeated.add(id);
            }
        }
        if (!repeated.isEmpty()) {
            throw new ConflictException("duplicate_transaction_in_request",
                "transaction listed more than once: " + String.join(", ", repeated));
        }

        // 2. Ids we can't parse can't exist → treated the same as unknown ids (404).
        List<Long> ids = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String publicId : publicIds) {
            Optional<Long> id = PublicIds.parseTransaction(publicId);
            id.ifPresentOrElse(ids::add, () -> missing.add(publicId));
        }

        // 3. Lock the rows (sorted id order, see lockForUpdate) so nobody edits them while we copy.
        Set<Long> found = new HashSet<>(transactions.lockForUpdate(ids));
        ids.stream().filter(id -> !found.contains(id)).map(PublicIds::transaction).forEach(missing::add);
        if (!missing.isEmpty()) {
            throw new NotFoundException("transactions not found: " + String.join(", ", missing));
        }

        // 4. Load them fully, put them back in request order, let the assembler do the maths
        //    (it also rejects mixed currency with 409).
        Map<Long, Transaction> byId = transactions.findByIds(ids).stream()
            .collect(Collectors.toMap(Transaction::id, Function.identity()));
        List<Transaction> inRequestOrder = ids.stream().map(byId::get).toList();
        return assembler.assemble(inRequestOrder);
    }

    private Invoice load(long id) {
        return invoices.findByIds(List.of(id)).stream()
            .findFirst()
            .orElseThrow(() -> notFound(id));
    }

    private static NotFoundException notFound(long id) {
        return new NotFoundException("invoice " + PublicIds.invoice(id) + " not found");
    }
}
