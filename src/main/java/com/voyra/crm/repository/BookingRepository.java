package com.voyra.crm.repository;

import com.voyra.crm.entity.Booking;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, String> {

    List<Booking> findByAgentId(String agentId);

    List<Booking> findByClientId(String clientId);

    List<Booking> findByType(BookingType type);

    List<Booking> findByBookingStatus(BookingStatus bookingStatus);

    List<Booking> findByAgentIdAndType(String agentId, BookingType type);

    List<Booking> findByAgentIdAndBookingStatus(String agentId, BookingStatus bookingStatus);

    List<Booking> findByTypeAndBookingStatus(BookingType type, BookingStatus bookingStatus);

    List<Booking> findByAgentIdAndTypeAndBookingStatus(String agentId, BookingType type, BookingStatus bookingStatus);

    Page<Booking> findByAgentId(String agentId, Pageable pageable);

    Page<Booking> findByType(BookingType type, Pageable pageable);

    Page<Booking> findByBookingStatus(BookingStatus bookingStatus, Pageable pageable);

    Page<Booking> findByAgentIdAndType(String agentId, BookingType type, Pageable pageable);

    Page<Booking> findByAgentIdAndBookingStatus(String agentId, BookingStatus bookingStatus, Pageable pageable);

    Page<Booking> findByTypeAndBookingStatus(BookingType type, BookingStatus bookingStatus, Pageable pageable);

    Page<Booking> findByAgentIdAndTypeAndBookingStatus(String agentId, BookingType type, BookingStatus bookingStatus, Pageable pageable);

    long countByAgentId(String agentId);

    long countByAgentIdAndBookingStatus(String agentId, BookingStatus bookingStatus);

    long countByBookingStatus(BookingStatus bookingStatus);

    @Query("""
            SELECT COALESCE(SUM(b.sellingPrice), 0) AS totalRevenue, COALESCE(SUM(b.profit), 0) AS totalProfit,
                   COALESCE(SUM(b.netCost), 0) AS totalNetCost
            FROM Booking b WHERE b.agentId = :agentId
            """)
    AgentRevenueProjection sumRevenueByAgentId(@Param("agentId") String agentId);

    @Query("""
            SELECT COALESCE(SUM(b.sellingPrice), 0) AS totalRevenue, COALESCE(SUM(b.profit), 0) AS totalProfit,
                   COALESCE(SUM(b.netCost), 0) AS totalNetCost
            FROM Booking b
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
            WHERE b.agentId IN :agentIds
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
            WHERE b.bookingDate >= :from
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
}
