package com.voyra.crm.service;

import com.voyra.crm.dto.CustomerCreateRequest;
import com.voyra.crm.dto.CustomerDetailResponse;
import com.voyra.crm.dto.CustomerLookupResponse;
import com.voyra.crm.dto.CustomerResponse;
import com.voyra.crm.dto.CustomerUpdateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.entity.CustomerInteraction;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientInvoiceRepository;
import com.voyra.crm.repository.CustomerInteractionRepository;
import com.voyra.crm.repository.VisaRepository;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private static final Set<LeadStatus> TERMINAL_STATUSES = Set.of(LeadStatus.BOOKED, LeadStatus.LOST);

    private final CustomerRepository customerRepository;
    private final AgentRepository agentRepository;
    private final BookingRepository bookingRepository;
    private final ClientInvoiceRepository clientInvoiceRepository;
    private final VisaRepository visaRepository;
    private final LeadRepository leadRepository;
    private final CustomerInteractionRepository interactionRepository;
    private final CustomerDetailAssembler detailAssembler;
    private final AuthorResolver authorResolver;

    @Transactional
    public CustomerResponse createCustomer(CustomerCreateRequest request) {
        AuthorResolver.AuthorInfo owner = resolveOwningAgent(request.getAgentId());

        Customer customer = Customer.builder()
                .id(generateUniqueCustomerId())
                .agentId(owner.id())
                .agentName(owner.name())
                .createdDate(LocalDateTime.now())
                .name(request.getName())
                .email(request.getEmail())
                .countryCode(request.getCountryCode())
                .phone(request.getPhone())
                .dob(request.getDob())
                .gender(request.getGender())
                .city(request.getCity())
                .country(request.getCountry())
                .nationality(request.getNationality())
                .passportNumber(request.getPassportNumber())
                .passportExpiry(request.getPassportExpiry())
                .preferredAirline(request.getPreferredAirline())
                .preferredCabin(request.getPreferredCabin())
                .status(request.getStatus())
                .tags(request.getTags() != null ? request.getTags() : List.of())
                .build();
        customerRepository.save(customer);

        if (request.getNotes() != null && !request.getNotes().isBlank()) {
            AuthorResolver.AuthorInfo author = authorResolver.resolveCurrentAuthor();
            interactionRepository.save(CustomerInteraction.builder()
                    .id(UniqueIdResolver.resolve(interactionRepository::existsById))
                    .customerId(customer.getId())
                    .authorAgentId(author.id())
                    .authorName(author.name())
                    .note(request.getNotes())
                    .createdDate(LocalDateTime.now())
                    .build());
        }

        log.info("Customer created: customerId={}, agentId={}", customer.getId(), owner.id());
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> listCustomers(String search) {
        List<Customer> customers = scopedList(search);
        return customers.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CustomerDetailResponse getCustomer(String id) {
        Customer customer = findAccessibleCustomer(id);
        return detailAssembler.assemble(customer);
    }

    @Transactional
    public CustomerResponse updateCustomer(String id, CustomerUpdateRequest request) {
        Customer customer = findAccessibleCustomer(id);
        boolean nameChanged = request.getName() != null && !request.getName().equals(customer.getName());

        if (request.getName() != null) customer.setName(request.getName());
        if (request.getEmail() != null) customer.setEmail(request.getEmail());
        if (request.getCountryCode() != null) customer.setCountryCode(request.getCountryCode());
        if (request.getPhone() != null) customer.setPhone(request.getPhone());
        if (request.getDob() != null) customer.setDob(request.getDob());
        if (request.getGender() != null) customer.setGender(request.getGender());
        if (request.getCity() != null) customer.setCity(request.getCity());
        if (request.getCountry() != null) customer.setCountry(request.getCountry());
        if (request.getNationality() != null) customer.setNationality(request.getNationality());
        if (request.getPassportNumber() != null) customer.setPassportNumber(request.getPassportNumber());
        if (request.getPassportExpiry() != null) customer.setPassportExpiry(request.getPassportExpiry());
        if (request.getPreferredAirline() != null) customer.setPreferredAirline(request.getPreferredAirline());
        if (request.getPreferredCabin() != null) customer.setPreferredCabin(request.getPreferredCabin());
        if (request.getStatus() != null) customer.setStatus(request.getStatus());
        if (request.getTags() != null) customer.setTags(request.getTags());

        customerRepository.save(customer);

        // Live-sync denormalized name snapshots in the same transaction as the rename.
        if (nameChanged) {
            bookingRepository.updateCustomerNameForCustomer(customer.getId(), customer.getName());
            clientInvoiceRepository.updateCustomerNameForCustomer(customer.getId(), customer.getName());
            visaRepository.updateCustomerNameForCustomer(customer.getId(), customer.getName());
        }

        log.info("Customer updated: customerId={}", id);
        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public CustomerLookupResponse lookupByPhone(String countryCode, String phone) {
        String digitsOnly = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digitsOnly.isBlank()) {
            return CustomerLookupResponse.builder().matched(false).build();
        }
        return customerRepository.findByPhoneEndingWith(digitsOnly).stream()
                .findFirst()
                .map(c -> CustomerLookupResponse.builder()
                        .matched(true).customerId(c.getId()).name(c.getName()).email(c.getEmail()).build())
                .orElse(CustomerLookupResponse.builder().matched(false).build());
    }

    /** Resolves who a new customer/family-member/interaction belongs to, given the caller's role. */
    private AuthorResolver.AuthorInfo resolveOwningAgent(String requestedAgentId) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent()) {
            Agent agent = agentRepository.findById(principal.userId())
                    .orElseThrow(() -> new IllegalStateException("Agent not found: " + principal.userId()));
            return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
        }
        // AGENCY_OWNER must specify which agent owns this customer.
        if (requestedAgentId == null || requestedAgentId.isBlank()) {
            throw new IllegalArgumentException("agentId is required when an Owner creates a customer");
        }
        Agent agent = agentRepository.findByIdAndTenantId(requestedAgentId, principal.tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Agent not found in this agency: " + requestedAgentId));
        return new AuthorResolver.AuthorInfo(agent.getId(), agent.getName());
    }

    private List<Customer> scopedList(String search) {
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        List<Customer> base = principal.isAgent()
                ? customerRepository.findByAgentId(principal.userId())
                : customerRepository.findAll();
        if (search == null || search.isBlank()) {
            return base;
        }
        String needle = search.toLowerCase();
        return base.stream()
                .filter(c -> containsIgnoreCase(c.getName(), needle)
                        || containsIgnoreCase(c.getEmail(), needle)
                        || containsIgnoreCase(c.getPhone(), needle))
                .toList();
    }

    private boolean containsIgnoreCase(String value, String needleLower) {
        return value != null && value.toLowerCase().contains(needleLower);
    }

    /** Shared ownership check reused by family-member/document/interaction sub-resource services. */
    public Customer findAccessibleCustomer(String id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + id));
        CustomUserPrincipal principal = SecurityContextUtil.getCurrentUserOrThrow();
        if (principal.isAgent() && !customer.getAgentId().equals(principal.userId())) {
            throw new AccessDeniedException("This customer is not assigned to you");
        }
        return customer;
    }

    private CustomerResponse toResponse(Customer c) {
        return CustomerResponse.builder()
                .id(c.getId())
                .agentId(c.getAgentId())
                .agentName(c.getAgentName())
                .name(c.getName())
                .email(c.getEmail())
                .countryCode(c.getCountryCode())
                .phone(c.getPhone())
                .dob(c.getDob())
                .gender(c.getGender())
                .city(c.getCity())
                .country(c.getCountry())
                .nationality(c.getNationality())
                .passportNumber(c.getPassportNumber())
                .passportExpiry(c.getPassportExpiry())
                .preferredAirline(c.getPreferredAirline())
                .preferredCabin(c.getPreferredCabin())
                .status(c.getStatus())
                .tags(c.getTags())
                .createdDate(c.getCreatedDate())
                .build();
    }

    private String generateUniqueCustomerId() {
        return UniqueIdResolver.resolve(customerRepository::existsById);
    }
}
