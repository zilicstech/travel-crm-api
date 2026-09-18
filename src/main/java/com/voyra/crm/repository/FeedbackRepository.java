package com.voyra.crm.repository;

import com.voyra.crm.entity.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, String> {

    List<Feedback> findByClientIdOrderBySubmittedAtDesc(String clientId);

    Optional<Feedback> findByBookingId(String bookingId);
}
