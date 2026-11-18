package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.dto.FlightFareDayResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.FlightFareCalendarService;
import com.voyra.crm.util.FinancialYear;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Public V5 on a fresh database: the fare calendar's upsert keeps the last search's fare, and the range read. */
class FlightFareCalendarIT extends AbstractIntegrationTest {

    @Autowired
    private FlightFareCalendarService service;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM public.flight_fare_calendar");
    }

    private static FlightSearchQuery search(LocalDate day) {
        FlightSearchQuery q = new FlightSearchQuery();
        q.setOrigin("DEL");
        q.setDestination("BOM");
        q.setDepartDate(day);
        q.setAdults(1);
        return q;
    }

    private static List<FlightOfferResponse> fare(String published) {
        FlightOfferResponse o = new FlightOfferResponse();
        o.setPublishedFare(new BigDecimal(published));
        o.setBaseFare(new BigDecimal("1000"));
        return List.of(o);
    }

    @Test
    void theLastSearchOfADayWinsAndOnlyTheRequestedRouteAndRangeIsRead() {
        LocalDate d1 = FinancialYear.today().plusDays(5);
        LocalDate d2 = d1.plusDays(1);
        service.record(search(d1), fare("4000"));
        service.record(search(d1), fare("5200")); // dearer, but the newest search's least fare is what shows
        service.record(search(d2), fare("3900.50"));
        FlightSearchQuery back = search(d1);
        back.setOrigin("BOM");
        back.setDestination("DEL");
        service.record(back, fare("100"));

        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("u1", "owner", UserType.AGENCY_OWNER, "no-such-tenant"), null, List.of()));
        List<FlightFareDayResponse> days = service.read("DEL", "BOM", null, d1, d2);

        assertThat(days).extracting(FlightFareDayResponse::date).containsExactly(d1, d2);
        assertThat(days.get(0).fare()).isEqualByComparingTo("5200");
        assertThat(days.get(1).fare()).isEqualByComparingTo("3900.50");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flight_fare_calendar", Integer.class)).isEqualTo(3);
    }
}
