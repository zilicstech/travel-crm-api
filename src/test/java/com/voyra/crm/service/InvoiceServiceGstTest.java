package com.voyra.crm.service;

import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoiceResponse;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.repository.SupplierInvoiceRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** GST and payment status must always be server-computed, never trusted from client input. */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceGstTest {

    @Mock
    private ClientInvoiceRepository clientInvoiceRepository;
    @Mock
    private SupplierInvoiceRepository supplierInvoiceRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private AgentRepository agentRepository;

    @InjectMocks
    private InvoiceService invoiceService;

    @BeforeEach
    void authenticateAsAgent() {
        CustomUserPrincipal principal = new CustomUserPrincipal("A1", "liam", UserType.AGENT, "T1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void stubCustomerAndSave() {
        Customer customer = Customer.builder().id("K1").name("Jane Doe").build();
        when(customerRepository.findById("K1")).thenReturn(Optional.of(customer));
        when(clientInvoiceRepository.existsById(anyString())).thenReturn(false);
    }

    @Test
    void defaultGstRateIsEighteenPercent() {
        stubCustomerAndSave();
        ClientInvoiceCreateRequest request = new ClientInvoiceCreateRequest();
        request.setCustomerId("K1");
        request.setAmount(new BigDecimal("10000"));

        ClientInvoiceResponse response = invoiceService.createClientInvoice(request);

        assertThat(response.getGst()).isEqualByComparingTo("1800.00");
        assertThat(response.getTotalWithGst()).isEqualByComparingTo("11800.00");
    }

    @Test
    void explicitGstRateIsHonoured() {
        stubCustomerAndSave();
        ClientInvoiceCreateRequest request = new ClientInvoiceCreateRequest();
        request.setCustomerId("K1");
        request.setAmount(new BigDecimal("10000"));
        request.setGstRate(new BigDecimal("5"));

        ClientInvoiceResponse response = invoiceService.createClientInvoice(request);

        assertThat(response.getGst()).isEqualByComparingTo("500.00");
        assertThat(response.getTotalWithGst()).isEqualByComparingTo("10500.00");
    }

    @Test
    void newInvoiceIsAlwaysPendingWithZeroPaid() {
        stubCustomerAndSave();
        ClientInvoiceCreateRequest request = new ClientInvoiceCreateRequest();
        request.setCustomerId("K1");
        request.setAmount(new BigDecimal("10000"));

        ClientInvoiceResponse response = invoiceService.createClientInvoice(request);

        assertThat(response.getStatus()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(response.getAmountPaid()).isEqualByComparingTo("0");
    }
}
