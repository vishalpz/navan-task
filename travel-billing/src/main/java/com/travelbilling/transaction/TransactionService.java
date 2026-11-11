package com.travelbilling.transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.travelbilling.common.ConflictException;
import com.travelbilling.common.NotFoundException;
import com.travelbilling.common.Page;
import com.travelbilling.common.PublicIds;
import com.travelbilling.common.RequestValidationException;

@Service
public class TransactionService {

    private final TransactionRepository transactions;
    private final TransactionMetadataRepository metadataRepository;
    private final MetadataParser metadataParser;

    public TransactionService(TransactionRepository transactions,
                              TransactionMetadataRepository metadataRepository,
                              MetadataParser metadataParser) {
        this.transactions = transactions;
        this.metadataRepository = metadataRepository;
        this.metadataParser = metadataParser;
    }

    /**
     * Everything below runs in ONE database transaction. If any step throws, Postgres rolls back
     * all of it, including the header row from step 1, so the external_id is free again.
     */
    @Transactional
    public Transaction create(CreateTransactionRequest request) {
        // 1. Claim the external_id. The DB unique constraint is the referee.
        //    Identity is decided by external_id alone; the rest of the body is never compared.
        long id = transactions.insertIfAbsent(request)
            .orElseThrow(() -> new DuplicateExternalIdException(
                transactions.findIdByExternalId(request.externalId())
                    .orElseThrow(() -> new IllegalStateException(
                        "external_id conflicted but no row found: " + request.externalId()))));

        // 2. Type-specific rules, deliberately AFTER step 1 so a duplicate always gets 409,
        //    even if its body is incomplete (the fixture's duplicate_flight has coupons: []).
        TransactionMetadata metadata = metadataParser.parse(request.type(), request.metadata());

        // 3. Children.
        transactions.insertTaxLines(id, request.taxLines());
        transactions.insertFeeLines(id, request.feeLines());
        metadataRepository.insert(id, metadata);

        // 4. Return what's actually stored, not an echo of the request.
        return load(id);
    }

    /**
     * Partial update. Refused (409, nothing saved) once the transaction is on any invoice:
     * billed money must not be silently rewritten. To bill a corrected amount, delete or PATCH the
     * invoice first.
     *
     * The row lock serialises us with anyone else touching this transaction, in particular an
     * invoice being created from it. If the invoice gets the lock first, we wait, then see its lines
     * and refuse. If we get it first, the invoice waits and then copies our finished edit, never half of it.
     */
    @Transactional
    public Transaction update(long id, PatchTransactionRequest patch) {
        lockOrThrowNotFound(id);
        refuseIfInvoiced(id, "changed");
        Transaction current = load(id);

        List<String> errors = new ArrayList<>();
        if (patch.type() != null && patch.type() != current.type()) {
            errors.add("type: cannot be changed");
        }
        if (patch.externalId() != null && !patch.externalId().equals(current.externalId())) {
            errors.add("external_id: cannot be changed");
        }
        if (patch.currency() != null && !patch.currency().equals(current.currency())) {
            errors.add("currency: cannot be changed");
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        if (patch.changesNothing()) {
            return current;
        }

        // Metadata must match the transaction's (unchangeable) type, with the same rules as on create.
        TransactionMetadata metadata = patch.metadata() == null
            ? null
            : metadataParser.parse(current.type(), patch.metadata());

        transactions.updateHeader(id,
            patch.occurredAt() != null ? patch.occurredAt() : current.occurredAt(),
            patch.total() != null ? patch.total() : current.total());
        if (patch.taxLines() != null) {
            transactions.replaceTaxLines(id, patch.taxLines());
        }
        if (patch.feeLines() != null) {
            transactions.replaceFeeLines(id, patch.feeLines());
        }
        if (metadata != null) {
            metadataRepository.delete(id, current.type());
            metadataRepository.insert(id, metadata);
        }
        return load(id);
    }

    /**
     * Refused (409) while the transaction is on any invoice. Delete the invoice first.
     * (The DB's ON DELETE RESTRICT on invoice_lines would refuse it anyway; this check
     * just gives a clear message instead of a constraint error.)
     */
    @Transactional
    public void delete(long id) {
        // Lock first: if an invoice is being created from this transaction right now, we wait for it,
        // and the check below then sees its lines.
        lockOrThrowNotFound(id);
        refuseIfInvoiced(id, "deleted");
        transactions.delete(id);
    }

    /** Must be called AFTER the row lock, so an invoice being created right now is seen once it commits. */
    private void refuseIfInvoiced(long id, String action) {
        List<Long> invoiceIds = transactions.findInvoiceIdsContaining(id);
        if (!invoiceIds.isEmpty()) {
            throw new ConflictException("transaction_invoiced",
                "transaction " + PublicIds.transaction(id) + " is on invoice(s) "
                    + invoiceIds.stream().map(PublicIds::invoice).collect(Collectors.joining(", "))
                    + " and cannot be " + action + "; delete or PATCH those invoices first");
        }
    }

    /**
     * Read-only, REPEATABLE READ: the several SELECTs that build one transaction all see the same
     * snapshot, so a concurrent PATCH (Phase 4) can't give us a header from before and lines from after.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Transaction get(long id) {
        return load(id);
    }

    /**
     * One page of transactions with id > afterId (0 = from the start). Same snapshot isolation as
     * get(), so a page is consistent even while other requests write.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Transaction> list(long afterId, int limit) {
        Page.checkLimit(limit);
        return Page.of(afterId, limit, transactions::findIdsAfter, transactions::findByIds, Transaction::id);
    }

    private void lockOrThrowNotFound(long id) {
        if (transactions.lockForUpdate(List.of(id)).isEmpty()) {
            throw new NotFoundException("transaction " + PublicIds.transaction(id) + " not found");
        }
    }

    private Transaction load(long id) {
        return transactions.findByIds(List.of(id)).stream()
            .findFirst()
            .orElseThrow(() -> new NotFoundException("transaction " + PublicIds.transaction(id) + " not found"));
    }
}
