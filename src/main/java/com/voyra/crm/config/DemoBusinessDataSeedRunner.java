package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.dto.CustomerCreateRequest;
import com.voyra.crm.dto.GuestDetails;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadDetailResponse;
import com.voyra.crm.dto.LeadStatusUpdateRequest;
import com.voyra.crm.dto.ProposalItemCreateRequest;
import com.voyra.crm.dto.SupplierInvoiceCreateRequest;
import com.voyra.crm.dto.SupplierInvoiceStatusUpdateRequest;
import com.voyra.crm.dto.VisaChecklistUpdateRequest;
import com.voyra.crm.dto.VisaCreateRequest;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.Booking;
import com.voyra.crm.entity.ClientInvoice;
import com.voyra.crm.entity.Customer;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.CustomerStatus;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.LeadCategory;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadSource;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.CustomerRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.CustomerService;
import com.voyra.crm.service.InvoiceService;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.VisaService;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Seeds realistic tenant-schema business data (customers/leads/bookings/invoices/visas) for
 * the demo agency, so every screen has something to show and the dashboards/reports have real
 * shape instead of rendering empty on a fresh database. Runs at Order(200) - strictly after
 * {@link DemoDataSeedRunner} (Order 0, creates the tenant/agent identities) and
 * {@link com.voyra.crm.migration.TenantMigrationStartupRunner} (Order 100, provisions the
 * tenant schema this class writes into).
 *
 * Calls through the real service layer (never touches entities directly for anything but
 * backdating - see below) so every server-computed field (booking profit, invoice GST/status,
 * visa status, lead margins) is derived exactly the way the API derives it for a real user.
 *
 * One deliberate exception: service create methods always stamp "now" as the creation/booking
 * date (there is no create-time override in the DTOs), which would put every seeded booking in
 * the current month and leave the revenue trend chart empty for the other 5. After each booking
 * is created through {@link BookingService}, its bookingDate/createdDate are backdated via a
 * direct repository save so the 6-month trend has real shape - amounts, profit, and every other
 * field are still exactly what the service computed.
 */
@Component
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
@Order(200)
public class DemoBusinessDataSeedRunner implements ApplicationRunner {

    private static final String DEMO_PASSWORD = "Passw0rd!";
    private static final String OWNER_EMAIL = "owner@globalexplorer.com";
    private static final String LIAM_EMAIL = "liam@globalexplorer.com";

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final PasswordEncoder passwordEncoder;

    private final CustomerRepository customerRepository;
    private final BookingRepository bookingRepository;

    private final CustomerService customerService;
    private final LeadService leadService;
    private final BookingService bookingService;
    private final InvoiceService invoiceService;
    private final VisaService visaService;

    @Override
    public void run(ApplicationArguments args) {
        Optional<Tenant> tenantOpt = tenantRepository.findByOwnerEmailIgnoreCase(OWNER_EMAIL);
        if (tenantOpt.isEmpty()) {
            log.warn("Demo tenant not found - skipping business data seed");
            return;
        }
        String tenantId = tenantOpt.get().getId();

        TenantContext.setTenantId(tenantId);
        setOwnerSecurityContext(tenantId);
        try {
            if (customerRepository.count() >= 8) {
                log.info("Demo business data already seeded for tenant {} - skipping", tenantId);
                return;
            }
            List<Agent> agents = ensureAgents(tenantId);
            List<String> customerIds = seedCustomers(agents);
            seedLeads(agents, customerIds);
            seedBookings(agents, customerIds);
            seedInvoices(agents, customerIds);
            seedVisas(agents, customerIds);
            log.info("Demo business data seed complete for tenant {}", tenantId);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContext.clear();
        }
    }

    private void setOwnerSecurityContext(String tenantId) {
        CustomUserPrincipal principal = new CustomUserPrincipal(tenantId, OWNER_EMAIL, UserType.AGENCY_OWNER, tenantId);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENCY_OWNER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---------------------------------------------------------------- agents

    private List<Agent> ensureAgents(String tenantId) {
        Agent liam = agentRepository.findByEmailIgnoreCase(LIAM_EMAIL)
                .orElseThrow(() -> new IllegalStateException("Expected demo agent Liam Smith to already be seeded"));
        Agent emma = ensureAgent(tenantId, "Emma Wilson", "emma.wilson.demo@globalexplorer.com",
                "+1 555 234 5678", AgentDepartment.OPERATIONS);
        Agent priya = ensureAgent(tenantId, "Priya Sharma", "priya.sharma.demo@globalexplorer.com",
                "+91 98765 43210", AgentDepartment.VISA);
        return List.of(liam, emma, priya);
    }

    private Agent ensureAgent(String tenantId, String name, String email, String phone, AgentDepartment department) {
        return agentRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            Agent agent = Agent.builder()
                    .id(UniqueIdResolver.resolve(agentRepository::existsById))
                    .tenantId(tenantId)
                    .name(name)
                    .email(email)
                    .phone(phone)
                    .department(department)
                    .password(passwordEncoder.encode(DEMO_PASSWORD))
                    .isActive(true)
                    .build();
            agentRepository.save(agent);
            log.info("Seeded demo agent '{}' (agentId={}): email={}", name, agent.getId(), email);
            return agent;
        });
    }

    // ------------------------------------------------------------- customers

    private record CustomerSeed(String name, String email, String phone, LocalDate dob, String gender, String city,
            String passportNumber, LocalDate passportExpiry, String preferredAirline, String preferredCabin,
            CustomerStatus status, List<String> tags, int agentIdx, long monthsAgo) {
    }

    private List<String> seedCustomers(List<Agent> agents) {
        List<CustomerSeed> seeds = List.of(
                new CustomerSeed("Arjun Mehta", "arjun.mehta.demo@example.com", "9812345601",
                        LocalDate.of(1988, 3, 12), "Male", "Mumbai", "M1122334", LocalDate.of(2031, 3, 12),
                        "Emirates", "Economy", CustomerStatus.CUSTOMER, List.of("Repeat Traveller"), 0, 5),
                new CustomerSeed("Priya Nair", "priya.nair.demo@example.com", "9812345602",
                        LocalDate.of(1990, 7, 22), "Female", "Bengaluru", "N2233445", LocalDate.of(2030, 7, 22),
                        "Qatar Airways", "Business", CustomerStatus.VIP, List.of("VIP", "Corporate"), 1, 4),
                new CustomerSeed("Rahul Verma", "rahul.verma.demo@example.com", "9812345603",
                        LocalDate.of(1985, 11, 2), "Male", "Delhi", null, null,
                        null, "Economy", CustomerStatus.LEAD, List.of(), 2, 1),
                new CustomerSeed("Ananya Iyer", "ananya.iyer.demo@example.com", "9812345604",
                        LocalDate.of(1993, 1, 18), "Female", "Chennai", "I3344556", LocalDate.of(2029, 1, 18),
                        "Singapore Airlines", "Business", CustomerStatus.CORPORATE, List.of("Corporate"), 0, 3),
                new CustomerSeed("Vikram Malhotra", "vikram.malhotra.demo@example.com", "9812345605",
                        LocalDate.of(1979, 6, 9), "Male", "Pune", "V4455667", LocalDate.of(2028, 6, 9),
                        "Emirates", "Economy", CustomerStatus.CUSTOMER, List.of(), 1, 2),
                new CustomerSeed("Kavya Reddy", "kavya.reddy.demo@example.com", "9812345606",
                        LocalDate.of(1995, 9, 30), "Female", "Hyderabad", "K5566778", LocalDate.of(2033, 9, 30),
                        "Etihad", "First", CustomerStatus.VIP, List.of("VIP"), 2, 0),
                new CustomerSeed("Rohan Kapoor", "rohan.kapoor.demo@example.com", "9812345607",
                        LocalDate.of(1991, 12, 5), "Male", "Jaipur", null, null,
                        null, "Economy", CustomerStatus.LEAD, List.of(), 0, 1),
                new CustomerSeed("Sneha Joshi", "sneha.joshi.demo@example.com", "9812345608",
                        LocalDate.of(1987, 4, 25), "Female", "Ahmedabad", "S6677889", LocalDate.of(2030, 4, 25),
                        "Vistara", "Economy", CustomerStatus.CUSTOMER, List.of("Repeat Traveller"), 1, 3)
        );

        return seeds.stream().map(s -> {
            CustomerCreateRequest req = new CustomerCreateRequest();
            req.setAgentId(agents.get(s.agentIdx()).getId());
            req.setName(s.name());
            req.setCountryCode("+91");
            req.setPhone(s.phone());
            req.setEmail(s.email());
            req.setDob(s.dob());
            req.setGender(s.gender());
            req.setCity(s.city());
            req.setCountry("India");
            req.setNationality("Indian");
            req.setPassportNumber(s.passportNumber());
            req.setPassportExpiry(s.passportExpiry());
            req.setPreferredAirline(s.preferredAirline());
            req.setPreferredCabin(s.preferredCabin());
            req.setStatus(s.status());
            req.setTags(s.tags());
            String id = customerService.createCustomer(req).getId();
            backdateCustomer(id, s.monthsAgo());
            return id;
        }).toList();
    }

    private void backdateCustomer(String id, long monthsAgo) {
        Customer c = customerRepository.findById(id).orElseThrow();
        c.setCreatedDate(LocalDateTime.now().minusMonths(monthsAgo).minusDays(3));
        customerRepository.save(c);
    }

    // ----------------------------------------------------------------- leads

    private record LeadSeed(String name, String phone, String destination, List<LeadCategory> categories,
            String budget, LeadStatus status, LeadSource source, LeadPriority priority, int agentIdx,
            long followUpDays, String lostReason, Integer customerIdx) {
    }

    private void seedLeads(List<Agent> agents, List<String> customerIds) {
        List<LeadSeed> seeds = List.of(
                new LeadSeed("Karan Singh", "9812346101", "Bali, Indonesia",
                        List.of(LeadCategory.HOLIDAY_PACKAGE, LeadCategory.HOTEL), "₹1,20,000 - ₹1,50,000",
                        LeadStatus.NEW, LeadSource.WEBSITE, LeadPriority.MEDIUM, 0, 5, null, null),
                new LeadSeed("Meera Pillai", "9812346102", "Paris, France",
                        List.of(LeadCategory.FLIGHT, LeadCategory.HOTEL), "₹2,00,000 - ₹2,50,000",
                        LeadStatus.CONTACTED, LeadSource.REFERRAL, LeadPriority.HIGH, 1, 2, null, null),
                new LeadSeed("Aditya Rao", "9812346103", "Singapore",
                        List.of(LeadCategory.HOLIDAY_PACKAGE), "₹90,000 - ₹1,10,000",
                        LeadStatus.QUALIFIED, LeadSource.SOCIAL_MEDIA, LeadPriority.MEDIUM, 2, 7, null, null),
                new LeadSeed("Ishita Bansal", "9812346104", "Bangkok, Thailand",
                        List.of(LeadCategory.FLIGHT, LeadCategory.HOTEL, LeadCategory.VISA), "₹1,50,000 - ₹1,80,000",
                        LeadStatus.PROPOSAL_SENT, LeadSource.WALK_IN, LeadPriority.HIGH, 0, -2, null, null),
                new LeadSeed("Priya Nair", "9812345602", "Maldives",
                        List.of(LeadCategory.HOLIDAY_PACKAGE, LeadCategory.HOTEL), "₹3,00,000 - ₹3,50,000",
                        LeadStatus.NEGOTIATING, LeadSource.WHATSAPP, LeadPriority.HIGH, 1, 1, null, 1),
                new LeadSeed("Ananya Iyer", "9812345604", "London, UK",
                        List.of(LeadCategory.FLIGHT, LeadCategory.HOTEL, LeadCategory.VISA), "₹4,50,000",
                        LeadStatus.BOOKED, LeadSource.PHONE_CALL, LeadPriority.HIGH, 2, 30, null, 3),
                new LeadSeed("Devansh Oberoi", "9812346107", "Switzerland",
                        List.of(LeadCategory.HOLIDAY_PACKAGE), "₹5,00,000",
                        LeadStatus.LOST, LeadSource.WEBSITE, LeadPriority.MEDIUM, 0, 10,
                        "Booked with a competitor agency", null),
                new LeadSeed("Neha Kulkarni", "9812346108", "Goa, India",
                        List.of(LeadCategory.HOTEL), "₹40,000 - ₹60,000",
                        LeadStatus.NEW, LeadSource.WALK_IN, LeadPriority.LOW, 1, 10, null, null),
                new LeadSeed("Yash Trivedi", "9812346109", "Kerala, India",
                        List.of(LeadCategory.HOLIDAY_PACKAGE, LeadCategory.HOTEL), "₹80,000 - ₹1,00,000",
                        LeadStatus.CONTACTED, LeadSource.SOCIAL_MEDIA, LeadPriority.MEDIUM, 2, -1, null, null),
                new LeadSeed("Simran Kaur", "9812346110", "Tokyo, Japan",
                        List.of(LeadCategory.FLIGHT, LeadCategory.HOTEL, LeadCategory.VISA), "₹2,80,000",
                        LeadStatus.QUALIFIED, LeadSource.REFERRAL, LeadPriority.HIGH, 0, 4, null, null),
                new LeadSeed("Aryan Chopra", "9812346111", "New York, USA",
                        List.of(LeadCategory.FLIGHT, LeadCategory.VISA), "₹3,20,000",
                        LeadStatus.PROPOSAL_SENT, LeadSource.OTHER, LeadPriority.MEDIUM, 1, -3, null, null),
                new LeadSeed("Rohan Kapoor", "9812345607", "Dubai, UAE",
                        List.of(LeadCategory.HOLIDAY_PACKAGE, LeadCategory.HOTEL), "₹1,60,000 - ₹1,90,000",
                        LeadStatus.NEGOTIATING, LeadSource.PHONE_CALL, LeadPriority.MEDIUM, 2, 6, null, 6)
        );

        for (LeadSeed s : seeds) {
            LeadCreateRequest req = new LeadCreateRequest();
            req.setAssignedTo(agents.get(s.agentIdx()).getId());
            req.setCountryCode("+91");
            req.setPhone(s.phone());
            req.setName(s.name());
            if (s.customerIdx() != null) {
                req.setCustomerId(customerIds.get(s.customerIdx()));
            }
            req.setDestination(s.destination());
            req.setTravelDateFrom(LocalDate.now().plusDays(45));
            req.setTravelDateTo(LocalDate.now().plusDays(52));
            req.setCategories(s.categories());
            req.setBudget(s.budget());
            req.setFollowUpDate(LocalDate.now().plusDays(s.followUpDays()));
            req.setSource(s.source());
            req.setPriority(s.priority());
            req.setGuestDetails(GuestDetails.builder().adults(2).children(0).infants(0).build());

            LeadDetailResponse created = leadService.createLead(req);

            if (s.status() != LeadStatus.NEW) {
                LeadStatusUpdateRequest statusReq = new LeadStatusUpdateRequest();
                statusReq.setStatus(s.status());
                if (s.status() == LeadStatus.LOST) {
                    statusReq.setLostReason(s.lostReason());
                }
                leadService.updateStatus(created.getId(), statusReq);
            }

            // A little texture on the two leads carrying a live proposal.
            if (s.name().equals("Ishita Bansal") || s.name().equals("Priya Nair")) {
                addProposalItems(created.getId());
            }
        }
    }

    private void addProposalItems(String leadId) {
        ProposalItemCreateRequest flight = new ProposalItemCreateRequest();
        flight.setType(ProposalItemType.FLIGHT);
        flight.setDescription("Round-trip economy flights");
        flight.setSupplier("Emirates");
        flight.setNetCost(new BigDecimal("45000"));
        flight.setSellingPrice(new BigDecimal("58000"));
        leadService.addProposalItem(leadId, flight);

        ProposalItemCreateRequest hotel = new ProposalItemCreateRequest();
        hotel.setType(ProposalItemType.HOTEL);
        hotel.setDescription("4-night 4-star hotel stay");
        hotel.setSupplier("Booking.com");
        hotel.setNetCost(new BigDecimal("38000"));
        hotel.setSellingPrice(new BigDecimal("49000"));
        leadService.addProposalItem(leadId, hotel);
    }

    // -------------------------------------------------------------- bookings

    private record BookingSeed(int customerIdx, BookingType type, String destination, BigDecimal netCost,
            BigDecimal sellingPrice, int agentIdx, long monthsAgo, BookingStatus targetStatus,
            PaymentStatus paymentStatus, String cancelReason) {
    }

    private void seedBookings(List<Agent> agents, List<String> customerIds) {
        List<BookingSeed> seeds = List.of(
                new BookingSeed(0, BookingType.FLIGHT, "Dubai, UAE", new BigDecimal("35000"), new BigDecimal("42000"),
                        0, 5, BookingStatus.COMPLETED, PaymentStatus.PAID, null),
                new BookingSeed(1, BookingType.HOTEL, "Bali, Indonesia", new BigDecimal("60000"), new BigDecimal("78000"),
                        1, 4, BookingStatus.CONFIRMED, PaymentStatus.PARTIAL, null),
                new BookingSeed(2, BookingType.PACKAGE, "Paris, France", new BigDecimal("150000"), new BigDecimal("190000"),
                        2, 4, BookingStatus.CONFIRMED, PaymentStatus.PAID, null),
                new BookingSeed(3, BookingType.VISA, "Singapore", new BigDecimal("8000"), new BigDecimal("12000"),
                        0, 3, BookingStatus.COMPLETED, PaymentStatus.PAID, null),
                new BookingSeed(4, BookingType.FLIGHT, "Bangkok, Thailand", new BigDecimal("22000"), new BigDecimal("28000"),
                        1, 3, BookingStatus.CANCELLED, PaymentStatus.REFUNDED,
                        "Customer preferred a different travel window"),
                new BookingSeed(5, BookingType.HOTEL, "Maldives", new BigDecimal("95000"), new BigDecimal("125000"),
                        2, 2, BookingStatus.CONFIRMED, PaymentStatus.PAID, null),
                new BookingSeed(6, BookingType.PACKAGE, "London, UK", new BigDecimal("180000"), new BigDecimal("230000"),
                        0, 1, BookingStatus.PENDING, PaymentStatus.PENDING, null),
                new BookingSeed(7, BookingType.FLIGHT, "Goa, India", new BigDecimal("15000"), new BigDecimal("19000"),
                        1, 1, BookingStatus.CONFIRMED, PaymentStatus.PAID, null),
                new BookingSeed(0, BookingType.HOTEL, "Kerala, India", new BigDecimal("40000"), new BigDecimal("52000"),
                        2, 0, BookingStatus.PENDING, PaymentStatus.PARTIAL, null),
                new BookingSeed(2, BookingType.PACKAGE, "Tokyo, Japan", new BigDecimal("210000"), new BigDecimal("265000"),
                        0, 0, BookingStatus.CONFIRMED, PaymentStatus.PAID, null)
        );

        for (BookingSeed s : seeds) {
            BookingCreateRequest req = new BookingCreateRequest();
            req.setCustomerId(customerIds.get(s.customerIdx()));
            req.setAgentId(agents.get(s.agentIdx()).getId());
            req.setType(s.type());
            req.setDestination(s.destination());
            req.setAirline(s.type() == BookingType.FLIGHT ? "Emirates" : null);
            req.setSupplier("Cleartrip");
            LocalDate journeyDate = LocalDate.now().minusMonths(s.monthsAgo()).plusDays(14);
            req.setJourneyDate(journeyDate);
            req.setReturnDate(journeyDate.plusDays(7));
            req.setTripType("Round Trip");
            req.setNetCost(s.netCost());
            req.setSellingPrice(s.sellingPrice());
            req.setPaymentStatus(s.paymentStatus());

            var created = bookingService.createBooking(req);

            if (s.targetStatus() != BookingStatus.PENDING) {
                BookingStatusUpdateRequest statusReq = new BookingStatusUpdateRequest();
                statusReq.setBookingStatus(s.targetStatus());
                if (s.targetStatus() == BookingStatus.CANCELLED) {
                    statusReq.setCancelReason(s.cancelReason());
                }
                bookingService.updateStatus(created.getId(), statusReq);
            }

            backdateBooking(created.getId(), s.monthsAgo());
        }
    }

    private void backdateBooking(String id, long monthsAgo) {
        Booking b = bookingRepository.findById(id).orElseThrow();
        LocalDate bookingDate = LocalDate.now().minusMonths(monthsAgo).minusDays(2);
        b.setBookingDate(bookingDate);
        b.setCreatedDate(bookingDate.atTime(10, 0));
        bookingRepository.save(b);
    }

    // -------------------------------------------------------------- invoices

    private record ClientInvoiceSeed(int customerIdx, int agentIdx, BigDecimal amount, long dueDays,
            String paymentMode, BigDecimal paymentAmount) {
    }

    private void seedInvoices(List<Agent> agents, List<String> customerIds) {
        List<ClientInvoiceSeed> clientSeeds = List.of(
                new ClientInvoiceSeed(0, 0, new BigDecimal("42000"), 10, "Bank Transfer", new BigDecimal("49560.00")),
                new ClientInvoiceSeed(1, 1, new BigDecimal("78000"), 5, "Credit Card", new BigDecimal("50000")),
                new ClientInvoiceSeed(2, 2, new BigDecimal("190000"), -5, "Bank Transfer", null),
                new ClientInvoiceSeed(4, 0, new BigDecimal("28000"), 15, "UPI", new BigDecimal("33040.00")),
                new ClientInvoiceSeed(5, 1, new BigDecimal("125000"), 20, "Bank Transfer", new BigDecimal("60000")),
                new ClientInvoiceSeed(7, 2, new BigDecimal("19000"), -2, "Cash", null)
        );

        for (ClientInvoiceSeed s : clientSeeds) {
            ClientInvoiceCreateRequest req = new ClientInvoiceCreateRequest();
            req.setCustomerId(customerIds.get(s.customerIdx()));
            req.setAgentId(agents.get(s.agentIdx()).getId());
            req.setAmount(s.amount());
            req.setDueDate(LocalDate.now().plusDays(s.dueDays()));
            req.setPaymentMode(s.paymentMode());
            var created = invoiceService.createClientInvoice(req);

            if (s.paymentAmount() != null) {
                ClientInvoicePaymentRequest payReq = new ClientInvoicePaymentRequest();
                payReq.setAmountPaid(s.paymentAmount());
                payReq.setPaymentMode(s.paymentMode());
                invoiceService.recordPayment(created.getId(), payReq);
            }
        }

        record SupplierSeed(String supplierName, BookingType category, BigDecimal amount, long dueDays,
                InvoiceStatus targetStatus) {
        }
        List<SupplierSeed> supplierSeeds = List.of(
                new SupplierSeed("Emirates Airlines", BookingType.FLIGHT, new BigDecimal("35000"), -3, InvoiceStatus.PENDING),
                new SupplierSeed("Taj Hotels", BookingType.HOTEL, new BigDecimal("58000"), 10, InvoiceStatus.PAID),
                new SupplierSeed("Kuoni Travel", BookingType.PACKAGE, new BigDecimal("145000"), 7, InvoiceStatus.PAID),
                new SupplierSeed("VFS Global", BookingType.VISA, new BigDecimal("7500"), 12, InvoiceStatus.PENDING)
        );
        for (SupplierSeed s : supplierSeeds) {
            SupplierInvoiceCreateRequest req = new SupplierInvoiceCreateRequest();
            req.setSupplierName(s.supplierName());
            req.setCategory(s.category());
            req.setAmount(s.amount());
            req.setDueDate(LocalDate.now().plusDays(s.dueDays()));
            var created = invoiceService.createSupplierInvoice(req);

            if (s.targetStatus() != InvoiceStatus.PENDING) {
                SupplierInvoiceStatusUpdateRequest statusReq = new SupplierInvoiceStatusUpdateRequest();
                statusReq.setStatus(s.targetStatus());
                invoiceService.updateSupplierInvoiceStatus(created.getId(), statusReq);
            }
        }
    }

    // ----------------------------------------------------------------- visas

    private record VisaSeed(int customerIdx, int agentIdx, String country, String visaType, String passportNumber,
            long applicationDaysAgo, String stage) {
    }

    private void seedVisas(List<Agent> agents, List<String> customerIds) {
        List<VisaSeed> seeds = List.of(
                new VisaSeed(0, 0, "United Arab Emirates", "Tourist", "M1122334", 3, "DOCUMENTS_PENDING"),
                new VisaSeed(1, 1, "Schengen (France)", "Tourist", "N2233445", 10, "APPOINTMENT_SCHEDULED"),
                new VisaSeed(3, 2, "Singapore", "Tourist", "I3344556", 15, "SUBMITTED"),
                new VisaSeed(5, 0, "United Kingdom", "Tourist", "K5566778", 30, "APPROVED"),
                new VisaSeed(2, 1, "United States", "Business", "R7788990", 20, "REJECTED")
        );

        for (VisaSeed s : seeds) {
            VisaCreateRequest req = new VisaCreateRequest();
            req.setCustomerId(customerIds.get(s.customerIdx()));
            req.setAgentId(agents.get(s.agentIdx()).getId());
            req.setCountry(s.country());
            req.setVisaType(s.visaType());
            req.setPassportNumber(s.passportNumber());
            req.setApplicationDate(LocalDate.now().minusDays(s.applicationDaysAgo()));
            var created = visaService.createVisa(req);

            VisaChecklistUpdateRequest checklist = new VisaChecklistUpdateRequest();
            switch (s.stage()) {
                case "DOCUMENTS_PENDING" -> {
                    // Leave as created - no checklist progress yet.
                }
                case "APPOINTMENT_SCHEDULED" -> {
                    checklist.setPassportCollected(true);
                    checklist.setPhotosCollected(true);
                    checklist.setFormsFilled(true);
                    checklist.setAppointmentDate(LocalDate.now().plusDays(5));
                    visaService.updateChecklist(created.getId(), checklist);
                }
                case "SUBMITTED" -> {
                    checklist.setPassportCollected(true);
                    checklist.setPhotosCollected(true);
                    checklist.setFormsFilled(true);
                    checklist.setAppointmentDate(LocalDate.now().minusDays(5));
                    checklist.setBiometricsDone(true);
                    checklist.setSubmittedToEmbassy(true);
                    visaService.updateChecklist(created.getId(), checklist);
                }
                case "APPROVED" -> {
                    checklist.setPassportCollected(true);
                    checklist.setPhotosCollected(true);
                    checklist.setFormsFilled(true);
                    checklist.setAppointmentDate(LocalDate.now().minusDays(20));
                    checklist.setBiometricsDone(true);
                    checklist.setSubmittedToEmbassy(true);
                    checklist.setApproved(true);
                    checklist.setVisaValidity("6 months, multiple entry");
                    checklist.setExpiryDate(LocalDate.now().plusMonths(6));
                    checklist.setPassportReturned(true);
                    visaService.updateChecklist(created.getId(), checklist);
                }
                case "REJECTED" -> {
                    checklist.setPassportCollected(true);
                    checklist.setPhotosCollected(true);
                    checklist.setFormsFilled(true);
                    checklist.setSubmittedToEmbassy(true);
                    checklist.setRejected(true);
                    visaService.updateChecklist(created.getId(), checklist);
                }
                default -> { /* no-op */ }
            }
        }
    }
}
