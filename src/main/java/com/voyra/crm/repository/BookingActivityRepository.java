package com.voyra.crm.repository;

import com.voyra.crm.entity.BookingActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingActivityRepository extends JpaRepository<BookingActivity, String> {

    List<BookingActivity> findByBookingIdOrderByAtDesc(String bookingId);
}
