package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingDocumentRepository extends JpaRepository<BookingDocument, String> {

    List<BookingDocument> findByBookingIdOrderBySortOrderAsc(String bookingId);

    List<BookingDocument> findByBookingIdInOrderBySortOrderAsc(java.util.Collection<String> bookingIds);

    /** Bookings with an air ticket voucher starting or returning on the day (a return ticket may be its own voucher). */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT DISTINCT d.booking_id FROM booking_document d
            WHERE d.deleted_at IS NULL AND d.doc_type = 'E_TICKET'
              AND (d.details ->> 'journeyDate' = :day OR d.details ->> 'returnDate' = :day)
            """, nativeQuery = true)
    List<String> findBookingIdsWithTicketOn(@org.springframework.data.repository.query.Param("day") String day);

    Optional<BookingDocument> findByIdAndBookingId(String id, String bookingId);

    /** A derived delete needs its own transaction - the callers sit outside one (they touch object storage). */
    @org.springframework.transaction.annotation.Transactional
    void deleteByBookingId(String bookingId);
}
