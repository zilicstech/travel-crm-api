package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingDocumentRepository extends JpaRepository<BookingDocument, String> {

    List<BookingDocument> findByBookingIdOrderBySortOrderAsc(String bookingId);

    Optional<BookingDocument> findByIdAndBookingId(String id, String bookingId);

    /** A derived delete needs its own transaction - the callers sit outside one (they touch object storage). */
    @org.springframework.transaction.annotation.Transactional
    void deleteByBookingId(String bookingId);
}
