package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.enums.CustomerStatus;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.service.AgencyService;
import com.voyra.crm.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NOT VERIFIED: Docker is unavailable on the machine this was written on, so this class has
 * never actually been run. Written carefully against the real service/repository signatures,
 * but treat it as a draft to run and fix, not a passing test, until executed once with a
 * working Docker daemon (blueprint §3.5, §10).
 *
 * Converts PROGRESS.md's manually-verified isolation claim into an executable one: two real
 * provisioned tenant schemas, and guessing a valid ID from the other tenant returns nothing,
 * because search_path never points at the wrong schema.
 */
class TenantIsolationIT extends AbstractIntegrationTest {

    @Autowired
    private AgencyService agencyService;
    @Autowired
    private LeadRepository leadRepository;
    @Autowired
    private CustomerRepository customerRepository;

    private String provisionTenant(String agencyName, String ownerEmail) {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName(agencyName);
        request.setOwnerName("Owner " + agencyName);
        request.setOwnerEmail(ownerEmail);
        AgencyCreateResponse response = agencyService.createAgency(request);
        return response.getId();
    }

    private String createLeadInTenant(String tenantId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            Lead lead = Lead.builder()
                    .id(IdGenerator.generateId())
                    .name("Isolation Test Lead")
                    .phone("9999999999")
                    .destination("Nowhere")
                    .status(LeadStatus.NEW)
                    .source(LeadSource.PHONE_CALL)
                    .priority(LeadPriority.MEDIUM)
                    .categories(List.of())
                    .assignedTo("A1")
                    .assignedAgentName("Test Agent")
                    .createdDate(LocalDateTime.now())
                    .build();
            return leadRepository.save(lead).getId();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    private String createCustomerInTenant(String tenantId) {
        String previous = TenantContext.getTenantId();
        try {
            TenantContext.setTenantId(tenantId);
            Customer customer = Customer.builder()
                    .id(IdGenerator.generateId())
                    .agentId("A1")
                    .agentName("Test Agent")
                    .name("Isolation Test Customer")
                    .status(CustomerStatus.LEAD)
                    .tags(List.of())
                    .createdDate(LocalDateTime.now())
                    .build();
            return customerRepository.save(customer).getId();
        } finally {
            if (previous == null || previous.isBlank()) {
                TenantContext.clear();
            } else {
                TenantContext.setTenantId(previous);
            }
        }
    }

    @Test
    void guessingAValidLeadIdFromAnotherTenantReturnsNothing() {
        String tenantA = provisionTenant("Isolation Agency A", "owner-a@isolation-test.com");
        String tenantB = provisionTenant("Isolation Agency B", "owner-b@isolation-test.com");

        String leadIdInA = createLeadInTenant(tenantA);

        TenantContext.setTenantId(tenantB);
        try {
            assertThat(leadRepository.findById(leadIdInA)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void guessingAValidCustomerIdFromAnotherTenantReturnsNothingInEitherDirection() {
        String tenantA = provisionTenant("Isolation Agency C", "owner-c@isolation-test.com");
        String tenantB = provisionTenant("Isolation Agency D", "owner-d@isolation-test.com");

        String customerIdInA = createCustomerInTenant(tenantA);
        String customerIdInB = createCustomerInTenant(tenantB);

        TenantContext.setTenantId(tenantB);
        try {
            assertThat(customerRepository.findById(customerIdInA)).isEmpty();
        } finally {
            TenantContext.clear();
        }

        TenantContext.setTenantId(tenantA);
        try {
            assertThat(customerRepository.findById(customerIdInB)).isEmpty();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void tenantContextDoesNotLeakAcrossThreadLocalBoundary() {
        String tenantA = provisionTenant("Isolation Agency E", "owner-e@isolation-test.com");

        TenantContext.setTenantId(tenantA);
        TenantContext.clear();

        assertThat(TenantContext.getTenantId()).isNull();
    }
}
