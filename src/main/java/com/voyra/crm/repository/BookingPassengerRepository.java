package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingPassenger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingPassengerRepository extends JpaRepository<BookingPassenger, String> {

    List<BookingPassenger> findByBookingIdOrderBySortOrderAsc(String bookingId);

    void deleteByBookingId(String bookingId);
}
