package com.voyra.crm.repository;

import com.voyra.crm.entity.BoardingPass;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BoardingPassRepository extends JpaRepository<BoardingPass, String> {

    Optional<BoardingPass> findByBookingIdAndTravelDate(String bookingId, LocalDate travelDate);

    List<BoardingPass> findByBookingIdInAndTravelDate(Collection<String> bookingIds, LocalDate travelDate);

    List<BoardingPass> findByBookingId(String bookingId);
}
