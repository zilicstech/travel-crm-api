package com.voyra.crm.repository;

import com.voyra.crm.entity.FeedbackLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface FeedbackLinkRepository extends JpaRepository<FeedbackLink, String> {

    Optional<FeedbackLink> findFirstByBookingIdAndExpiresAtAfterOrderByExpiresAtDesc(String bookingId, LocalDateTime now);
}
