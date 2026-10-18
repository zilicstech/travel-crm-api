package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingSector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingSectorRepository extends JpaRepository<BookingSector, String> {

    List<BookingSector> findByBookingPassengerIdOrderBySortOrderAsc(String bookingPassengerId);

    List<BookingSector> findByBookingPassengerIdInOrderBySortOrderAsc(List<String> bookingPassengerIds);

    /** A derived delete needs its own transaction when a caller isn't already in one. */
    @org.springframework.transaction.annotation.Transactional
    void deleteByBookingPassengerIdIn(List<String> bookingPassengerIds);
}
