package com.voyra.crm.repository;

import com.voyra.crm.entity.NotificationOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, String> {

    /** PENDING rows due now, and SENDING rows whose lease ran out (the instance sending them died). */
    @Query("SELECT o.id FROM NotificationOutbox o WHERE o.status IN (com.voyra.crm.enums.NotificationOutboxStatus.PENDING, "
            + "com.voyra.crm.enums.NotificationOutboxStatus.SENDING) AND o.nextAttemptAt <= :now ORDER BY o.nextAttemptAt")
    List<String> findDueIds(@Param("now") Instant now, Pageable pageable);

    /**
     * Claims one due row for this instance: 1 if claimed, 0 if another instance got it first. A concurrent claim blocks on the
     * row lock and re-checks the WHERE after the first commits, so it sees SENDING with a future lease and updates nothing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NotificationOutbox o SET o.status = com.voyra.crm.enums.NotificationOutboxStatus.SENDING, "
            + "o.attempts = o.attempts + 1, o.nextAttemptAt = :leaseUntil WHERE o.id = :id "
            + "AND o.status IN (com.voyra.crm.enums.NotificationOutboxStatus.PENDING, com.voyra.crm.enums.NotificationOutboxStatus.SENDING) "
            + "AND o.nextAttemptAt <= :now")
    int claim(@Param("id") String id, @Param("now") Instant now, @Param("leaseUntil") Instant leaseUntil);
}
