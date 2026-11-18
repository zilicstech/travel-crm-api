package com.voyra.crm.service;

import com.voyra.crm.dto.FlightFareDayResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FareCategory;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.service.booking.AgencyPriceQuoter;
import com.voyra.crm.service.booking.PriceQuote;
import com.voyra.crm.util.FinancialYear;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The flight fare calendar (owner rulings 2026-11-18): every one-way, adults-only, regular-fare search that every
 * supplier answered stores its cheapest bookable published fare per adult for the route, cabin and day, in
 * {@code public.flight_fare_calendar} (the database, never memory). The next such search overwrites it. Each agency
 * reads it after its own discount, so one row serves every agency.
 */
@Slf4j
@Service
public class FlightFareCalendarService {

    /** Two months, the most the calendar shows at once. */
    static final int MAX_DAYS = 62;

    private static final String UPSERT = "INSERT INTO public.flight_fare_calendar "
            + "(origin, destination, cabin, depart_date, fare_per_adult, searched_at) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (origin, destination, cabin, depart_date) DO UPDATE "
            + "SET fare_per_adult = EXCLUDED.fare_per_adult, searched_at = EXCLUDED.searched_at";

    private final JdbcTemplate jdbc;
    private final AgencyPriceQuoter quoter;
    /** Tests set this to move time. */
    Clock clock = Clock.systemUTC();

    public FlightFareCalendarService(JdbcTemplate jdbc, AgencyPriceQuoter quoter) {
        this.jdbc = jdbc;
        this.quoter = quoter;
    }

    /** Stores what a fresh supplier answer says the day costs; best effort, so it never fails the search. */
    public void record(FlightSearchQuery q, List<FlightOfferResponse> offers) {
        if (q.getReturnDate() != null || q.getChildren() > 0 || q.getInfants() > 0 || q.getAdults() < 1
                || FareCategory.orRegular(q.getFareCategory()) != FareCategory.REGULAR || q.getDepartDate() == null) {
            return; // no comparable per-adult fare
        }
        Optional<BigDecimal> cheapest = offers.stream()
                .filter(o -> o.getBaseFare() != null && o.getBaseFare().signum() > 0) // bookable: the agency price needs a base
                .map(FlightOfferResponse::getPublishedFare).filter(Objects::nonNull)
                .min(BigDecimal::compareTo);
        if (cheapest.isEmpty()) {
            return;
        }
        BigDecimal perAdult = cheapest.get().divide(BigDecimal.valueOf(q.getAdults()), 2, RoundingMode.HALF_UP);
        try {
            jdbc.update(UPSERT, code(q.getOrigin()), code(q.getDestination()), cabin(q.getCabin()), Date.valueOf(q.getDepartDate()),
                    perAdult, Timestamp.from(clock.instant()));
        } catch (DataAccessException e) {
            log.warn("Flight fare calendar not updated: {}", e.getClass().getSimpleName());
        }
    }

    /** Days in [from, to] (from no earlier than India's today, at most 62 days) with a stored fare, at the caller agency's price. */
    public List<FlightFareDayResponse> read(String origin, String destination, FlightCabin cabin, LocalDate from, LocalDate to) {
        if (!isCode(origin) || !isCode(destination)) {
            throw new IllegalArgumentException("Airports must be 3-letter codes");
        }
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new IllegalArgumentException("The range must run forward and cover at most " + MAX_DAYS + " days");
        }
        LocalDate start = from.isBefore(FinancialYear.today()) ? FinancialYear.today() : from;
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();
        Optional<BiFunction<BigDecimal, BigDecimal, PriceQuote>> quote = quoter.quoterFor(tenantId, PricingProduct.FLIGHT);
        // shortcut: rows for past dates stay; delete them in a periodic job if the table grows
        return jdbc.query("SELECT depart_date, fare_per_adult FROM public.flight_fare_calendar WHERE origin = ? AND destination = ? "
                        + "AND cabin = ? AND depart_date BETWEEN ? AND ? ORDER BY depart_date",
                (rs, i) -> {
                    BigDecimal fare = rs.getBigDecimal(2);
                    // the displayed fare depends on the gross only; the base (commission) is not needed here
                    return new FlightFareDayResponse(rs.getDate(1).toLocalDate(),
                            quote.map(f -> f.apply(fare, BigDecimal.ZERO).displayed()).orElse(fare));
                },
                code(origin), code(destination), cabin(cabin), Date.valueOf(start), Date.valueOf(to));
    }

    private static boolean isCode(String s) {
        return s != null && s.trim().matches("[A-Za-z]{3}");
    }

    private static String code(String s) {
        return s.trim().toUpperCase(Locale.ROOT);
    }

    private static String cabin(FlightCabin c) {
        return (c == null ? FlightCabin.ECONOMY : c).name();
    }
}
