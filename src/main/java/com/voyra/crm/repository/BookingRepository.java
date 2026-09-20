package com.voyra.crm.repository;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String>, JpaSpecificationExecutor<Booking> {

    /** Owner-dashboard "my recent bookings" widget - deliberately the agent's own bookings only,
     *  unrelated to the wider service-access scoping in BookingService.listBookings. */
    List<Booking> findByAgentId(String agentId);

    List<Booking> findByClientId(String clientId);

    /** Every booking logged on one service, oldest first - a round trip is two rows here. */
    List<Booking> findByServiceIdOrderByCreatedDateAsc(String serviceId);

    List<Booking> findByLeadId(String leadId);

    /** Drives the auto-BOOKED/back-to-CONFIRMED transition: zero means nothing live remains. */
    long countByServiceIdAndBookingStatusNot(String serviceId, BookingStatus excludedStatus);

    long countByAgentId(String agentId);

    long countByAgentIdAndBookingStatus(String agentId, BookingStatus bookingStatus);

    long countByBookingStatus(BookingStatus bookingStatus);

    /** Cancelled bookings never sold anything - excluded from every revenue-shaped aggregate below. */
    @Query("""
            SELECT COALESCE(SUM(b.sellingPrice), 0) AS totalRevenue, COALESCE(SUM(b.profit), 0) AS totalProfit,
                   COALESCE(SUM(b.netCost), 0) AS totalNetCost
            FROM Booking b WHERE b.agentId = :agentId AND b.bookingStatus <> :#{T(com.voyra.crm.enums.BookingStatus).CANCELLED}
            """)
    AgentRevenueProjection sumRevenueByAgentId(@Param("agentId") String agentId);

    @Query("""
            SELECT COALESCE(SUM(b.sellingPrice), 0) AS totalRevenue, COALESCE(SUM(b.profit), 0) AS totalProfit,
                   COALESCE(SUM(b.netCost), 0) AS totalNetCost
            FROM Booking b WHERE b.bookingStatus <> :#{T(com.voyra.crm.enums.BookingStatus).CANCELLED}
            """)
    AgentRevenueProjection sumRevenueForAgency();

    interface AgentRevenueProjection {
        java.math.BigDecimal getTotalRevenue();

        java.math.BigDecimal getTotalProfit();

        java.math.BigDecimal getTotalNetCost();
    }

    @Query("""
            SELECT b.agentId AS agentId,
                   COUNT(b) AS bookingsCount,
                   COALESCE(SUM(b.sellingPrice), 0) AS totalRevenue,
                   COALESCE(SUM(b.profit), 0) AS totalProfit
            FROM Booking b
            WHERE b.agentId IN :agentIds AND b.bookingStatus <> :#{T(com.voyra.crm.enums.BookingStatus).CANCELLED}
            GROUP BY b.agentId
            """)
    List<AgentBookingStatsProjection> aggregateBookingStatsByAgent(@Param("agentIds") Collection<String> agentIds);

    interface AgentBookingStatsProjection {
        String getAgentId();
        long getBookingsCount();
        java.math.BigDecimal getTotalRevenue();
        java.math.BigDecimal getTotalProfit();
    }

    @Query("SELECT b.type AS category, COUNT(b) AS count FROM Booking b GROUP BY b.type")
    List<com.voyra.crm.repository.LeadRepository.CategoryCountProjection> countGroupedByType();

    @Query("""
            SELECT YEAR(b.bookingDate) AS year, MONTH(b.bookingDate) AS month,
                   COALESCE(SUM(b.sellingPrice), 0) AS revenue,
                   COALESCE(SUM(b.profit), 0) AS profit
            FROM Booking b
            WHERE b.bookingDate >= :from AND b.bookingStatus <> :#{T(com.voyra.crm.enums.BookingStatus).CANCELLED}
            GROUP BY YEAR(b.bookingDate), MONTH(b.bookingDate)
            """)
    List<MonthlyRevenueProjection> aggregateMonthlyRevenueSince(@Param("from") java.time.LocalDate from);

    interface MonthlyRevenueProjection {
        int getYear();
        int getMonth();
        java.math.BigDecimal getRevenue();
        java.math.BigDecimal getProfit();
    }

    /** Keeps the denormalized agent_name/client_name snapshots live-synced on rename. */
    @Modifying
    @Query("UPDATE Booking b SET b.agentName = :name WHERE b.agentId = :agentId")
    void updateAgentNameForAgent(@Param("agentId") String agentId, @Param("name") String name);

    @Modifying
    @Query("UPDATE Booking b SET b.clientName = :name WHERE b.clientId = :clientId")
    void updateClientNameForClient(@Param("clientId") String clientId, @Param("name") String name);

    /** booking.supplier is a live reference stored as a name string, not an FK - see VendorService. */
    @Modifying
    @Query("UPDATE Booking b SET b.supplier = :newName WHERE b.supplier = :oldName")
    void updateSupplierName(@Param("oldName") String oldName, @Param("newName") String newName);

    /** Keeps the denormalized service_agent_id/service_agent_name snapshots live-synced
     *  whenever a service is accepted, assigned, or reassigned - these two fields are what
     *  let agent-scoped booking visibility avoid a lead_service join per row. */
    @Modifying
    @Query("UPDATE Booking b SET b.serviceAgentId = :agentId, b.serviceAgentName = :agentName WHERE b.serviceId = :serviceId")
    void resyncServiceAgent(@Param("serviceId") String serviceId, @Param("agentId") String agentId, @Param("agentName") String agentName);

    @Modifying
    @Query("UPDATE Booking b SET b.serviceLabel = :label WHERE b.serviceId = :serviceId")
    void resyncServiceLabel(@Param("serviceId") String serviceId, @Param("label") String label);

    /** Owner-wide escalation feed - bookings whose ticketing time limit has passed. */
    List<Booking> findByBookingStatusNotInAndTicketingDeadlineLessThan(
            Collection<BookingStatus> excludedStatuses, java.time.LocalDate date);

    List<Booking> findByAgentIdAndBookingStatusNotInAndTicketingDeadlineLessThan(
            String agentId, Collection<BookingStatus> excludedStatuses, java.time.LocalDate date);

    /** Owner-wide escalation feed - bookings past their free-cancellation window. */
    List<Booking> findByBookingStatusNotInAndCancellationDeadlineLessThan(
            Collection<BookingStatus> excludedStatuses, java.time.LocalDate date);

    List<Booking> findByAgentIdAndBookingStatusNotInAndCancellationDeadlineLessThan(
            String agentId, Collection<BookingStatus> excludedStatuses, java.time.LocalDate date);

    /** Agency-wide calendar feed. */
    List<Booking> findByJourneyDateBetween(java.time.LocalDate from, java.time.LocalDate to);

    List<Booking> findByAgentIdAndJourneyDateBetween(String agentId, java.time.LocalDate from, java.time.LocalDate to);

    List<Booking> findByReturnDateBetween(java.time.LocalDate from, java.time.LocalDate to);

    List<Booking> findByAgentIdAndReturnDateBetween(String agentId, java.time.LocalDate from, java.time.LocalDate to);
}
