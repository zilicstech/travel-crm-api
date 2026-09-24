package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingSector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingSectorRepository extends JpaRepository<BookingSector, String> {

    List<BookingSector> findByBookingPassengerIdOrderBySortOrderAsc(String bookingPassengerId);

    List<BookingSector> findByBookingPassengerIdInOrderBySortOrderAsc(List<String> bookingPassengerIds);

    void deleteByBookingPassengerIdIn(List<String> bookingPassengerIds);
}
