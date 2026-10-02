package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingCostComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingCostComponentRepository extends JpaRepository<BookingCostComponent, String> {

    List<BookingCostComponent> findByBookingIdOrderBySortOrder(String bookingId);

    List<BookingCostComponent> findByBookingIdAndVendorId(String bookingId, String vendorId);

    void deleteByBookingId(String bookingId);
}
