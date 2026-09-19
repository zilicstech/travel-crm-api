package com.voyra.crm.repository;

import com.voyra.crm.entity.CreditNote;
import com.voyra.crm.enums.CreditNoteStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditNoteRepository extends JpaRepository<CreditNote, String>, JpaSpecificationExecutor<CreditNote> {

    /** Used to cap a new credit note so the invoice is never credited past its own grand total. */
    List<CreditNote> findByInvoiceIdAndStatus(String invoiceId, CreditNoteStatus status);
}
