package com.voyra.crm.service;

import com.voyra.crm.dto.FlightFareDayResponse;
import com.voyra.crm.dto.FlightOfferResponse;
import com.voyra.crm.dto.FlightSearchQuery;
import com.voyra.crm.enums.FareCategory;
import com.voyra.crm.enums.FlightCabin;
import com.voyra.crm.enums.PricingProduct;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.booking.AgencyPriceQuoter;
import com.voyra.crm.service.booking.PriceQuote;
import com.voyra.crm.util.FinancialYear;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlightFareCalendarServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgencyPriceQuoter quoter = mock(AgencyPriceQuoter.class);
    private final FlightFareCalendarService service = new FlightFareCalendarService(jdbc, quoter);
    private static final LocalDate DAY = LocalDate.of(2027, 1, 10);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static FlightSearchQuery oneWay(Consumer<FlightSearchQuery> change) {
        FlightSearchQuery q = new FlightSearchQuery();
        q.setOrigin(" del");
        q.setDestination("BOM");
        q.setDepartDate(DAY);
        q.setAdults(2);
        change.accept(q);
        return q;
    }

    private static FlightOfferResponse offer(String published, String base) {
        FlightOfferResponse o = new FlightOfferResponse();
        o.setPublishedFare(published == null ? null : new BigDecimal(published));
        o.setBaseFare(base == null ? null : new BigDecimal(base));
        return o;
    }

    @Test
    void storesTheCheapestBookablePublishedFarePerAdultForTheRouteCabinAndDay() {
        service.record(oneWay(q -> { }), List.of(offer("9000", "7000"), offer("8001", "6000"), offer("5000", null),
                offer("4000", "0"), offer(null, "100")));

        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO public.flight_fare_calendar"),
                eq("DEL"), eq("BOM"), eq("ECONOMY"), eq(Date.valueOf(DAY)), eq(new BigDecimal("4000.50")), any());
    }

    @Test
    void searchesWithoutAComparablePerAdultFareStoreNothing() {
        List<FlightOfferResponse> offers = List.of(offer("9000", "7000"));
        for (Consumer<FlightSearchQuery> c : List.<Consumer<FlightSearchQuery>>of(
                q -> q.setReturnDate(DAY.plusDays(3)), q -> q.setChildren(1), q -> q.setInfants(1), q -> q.setAdults(0),
                q -> q.setFareCategory(FareCategory.STUDENT), q -> q.setDepartDate(null))) {
            service.record(oneWay(c), offers);
        }
        service.record(oneWay(q -> q.setFareCategory(FareCategory.REGULAR)), List.of(offer("5000", null)));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void aDatabaseErrorNeverFailsTheSearch() {
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new QueryTimeoutException("slow"));
        service.record(oneWay(q -> q.setCabin(FlightCabin.BUSINESS)), List.of(offer("9000", "7000")));
    }

    @SuppressWarnings("unchecked")
    private List<FlightFareDayResponse> readAs(String tenantId, LocalDate from, LocalDate to, FlightCabin cabin) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new CustomUserPrincipal("u1", "owner", com.voyra.crm.enums.UserType.AGENCY_OWNER, tenantId), null, List.of()));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getDate(1)).thenReturn(Date.valueOf(DAY));
        when(rs.getBigDecimal(2)).thenReturn(new BigDecimal("5000.00"));
        List<Object> captured = new ArrayList<>();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(i -> {
            Object[] all = i.getArguments();
            for (int k = 2; k < all.length; k++) captured.add(all[k]);
            return List.of(((RowMapper<FlightFareDayResponse>) all[1]).mapRow(rs, 0));
        });
        List<FlightFareDayResponse> days = service.read("del", "BOM", cabin, from, to);
        lastArgs = captured;
        return days;
    }

    private List<Object> lastArgs;

    @Test
    void readsTheCallerAgencysPriceAndNeverBeforeToday() throws Exception {
        when(quoter.quoterFor("T1", PricingProduct.FLIGHT)).thenReturn(Optional.of((gross, base) ->
                new PriceQuote(gross.multiply(new BigDecimal("0.9")), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "p", BigDecimal.TEN, BigDecimal.ZERO)));
        LocalDate today = FinancialYear.today();

        List<FlightFareDayResponse> days = readAs("T1", today.minusDays(5), today.plusDays(10), null);

        assertThat(days).singleElement().satisfies(d -> {
            assertThat(d.date()).isEqualTo(DAY);
            assertThat(d.fare()).isEqualByComparingTo("4500");
        });
        assertThat(lastArgs).containsExactly("DEL", "BOM", "ECONOMY", Date.valueOf(today), Date.valueOf(today.plusDays(10)));
    }

    @Test
    void withoutPricingRulesThePublishedFareShows() throws Exception {
        when(quoter.quoterFor("T2", PricingProduct.FLIGHT)).thenReturn(Optional.empty());
        LocalDate from = FinancialYear.today().plusDays(1);
        assertThat(readAs("T2", from, from.plusDays(61), FlightCabin.BUSINESS)).extracting(FlightFareDayResponse::fare)
                .containsExactly(new BigDecimal("5000.00"));
        assertThat(lastArgs.get(2)).isEqualTo("BUSINESS");
        assertThat(lastArgs.get(3)).isEqualTo(Date.valueOf(from));
    }

    @Test
    void refusesBadAirportsAndRanges() {
        LocalDate d = FinancialYear.today();
        for (String[] route : new String[][]{{null, "BOM"}, {"DE", "BOM"}, {"DEL", "B0M"}}) {
            assertThatThrownBy(() -> service.read(route[0], route[1], null, d, d)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Airports must be 3-letter codes");
        }
        assertThatThrownBy(() -> service.read("DEL", "BOM", null, d, d.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.read("DEL", "BOM", null, d, d.plusDays(62))).isInstanceOf(IllegalArgumentException.class);
    }
}
