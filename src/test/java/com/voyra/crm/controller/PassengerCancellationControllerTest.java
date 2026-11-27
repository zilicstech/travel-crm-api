package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.PassengerCancelRequest;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.PassengerCancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PassengerCancellationController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class PassengerCancellationControllerTest {

    private static final String BODY = "{\"travellerIds\":[\"M1\"],\"sellingShare\":100,\"purchaseShare\":90,\"agencyFee\":5}";

    @Autowired private MockMvc mvc;
    @MockBean private PassengerCancellationService service;

    @Test
    void ownerAndAgentCancelPassengersAndTheAccountantCannot() throws Exception {
        mvc.perform(json(post("/api/bookings/B1/cancel-passengers"), BODY).with(as(UserType.AGENT))).andExpect(status().isOk());
        verify(service).cancelPassengers("B1", PassengerCancelRequest.builder().travellerIds(List.of("M1"))
                .sellingShare(new BigDecimal("100")).purchaseShare(new BigDecimal("90")).agencyFee(new BigDecimal("5")).build());
        mvc.perform(json(post("/api/bookings/B1/cancel-passengers"), BODY).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
    }

    @Test
    void theAccountantIsRefusedAndAnEmptyPickIsABadRequest() throws Exception {
        mvc.perform(json(post("/api/bookings/B1/cancel-passengers"), BODY).with(as(UserType.ACCOUNTANT))).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/bookings/B1/cancel-passengers"), "{\"travellerIds\":[],\"sellingShare\":-1}").with(as(UserType.AGENT)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.errors.travellerIds").value("Pick the travellers to cancel"))
                .andExpect(jsonPath("$.details.errors.purchaseShare").value("Enter their share of the purchase price"));
        verifyNoInteractions(service);
    }
}
