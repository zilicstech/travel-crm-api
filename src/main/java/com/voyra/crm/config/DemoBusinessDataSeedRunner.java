package com.voyra.crm.config;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.BookingCreateRequest;
import com.voyra.crm.dto.BookingStatusUpdateRequest;
import com.voyra.crm.dto.ClientInvoiceCreateRequest;
import com.voyra.crm.dto.ClientInvoicePaymentRequest;
import com.voyra.crm.dto.ClientCreateRequest;
import com.voyra.crm.dto.GuestDetails;
import com.voyra.crm.dto.LeadCreateRequest;
import com.voyra.crm.dto.LeadMemberAddRequest;
import com.voyra.crm.dto.LeadMemberUpdateRequest;
import com.voyra.crm.dto.MemberCreateRequest;
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
import com.voyra.crm.entity.Client;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.enums.BookingStatus;
import com.voyra.crm.enums.BookingType;
import com.voyra.crm.enums.ClientType;
import com.voyra.crm.enums.InvoiceStatus;
import com.voyra.crm.enums.LeadMemberStatus;
import com.voyra.crm.enums.LeadPriority;
import com.voyra.crm.enums.LeadStatus;
import com.voyra.crm.enums.MemberRelation;
import com.voyra.crm.enums.PaymentStatus;
import com.voyra.crm.enums.ProposalItemType;
import com.voyra.crm.enums.ServiceStatus;
import com.voyra.crm.enums.ServiceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.dto.FollowUpCreateRequest;
import com.voyra.crm.dto.ServiceAssignRequest;
import com.voyra.crm.dto.ServiceDraft;
import com.voyra.crm.dto.ServiceStatusUpdateRequest;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.BookingRepository;
import com.voyra.crm.repository.ClientRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.security.CustomUserPrincipal;
import com.voyra.crm.service.BookingService;
import com.voyra.crm.service.ClientService;
import com.voyra.crm.service.MemberService;
import com.voyra.crm.service.InvoiceService;
import com.voyra.crm.service.LeadFollowUpService;
import com.voyra.crm.service.LeadService;
import com.voyra.crm.service.ServiceInstanceService;
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
 * Seeds realistic tenant-schema business data (clients/members/leads/bookings/invoices/visas) for
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

    private final ClientRepository clientRepository;
    private final BookingRepository bookingRepository;

    private final ClientService clientService;
    private final MemberService memberService;
    private final LeadService leadService;
    private final BookingService bookingService;
    private final InvoiceService invoiceService;
    private final VisaService visaService;
    private final ServiceInstanceService serviceInstanceService;
    private final LeadFollowUpService leadFollowUpService;

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
            if (clientRepository.count() >= 8) {
                log.info("Demo business data already seeded for tenant {} - skipping", tenantId);
                return;
            }
            List<Agent> agents = ensureAgents(tenantId);
            List<String> clientIds = seedClients(agents);
            List<String> leadIds = seedLeads(agents, clientIds, tenantId);
            seedServicesAndFollowUps(agents, leadIds, tenantId);
            seedBookings(agents, clientIds);
            seedInvoices(agents, clientIds);
            seedVisas(agents, clientIds);
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

    /** So a seeded lead's createdBy actually varies by agent instead of always being the
     *  Owner - createLead() reads whoever is currently authenticated, there is no explicit
     *  "create on behalf of" parameter any more. */
    private void setAgentSecurityContext(Agent agent, String tenantId) {
        CustomUserPrincipal principal = new CustomUserPrincipal(agent.getId(), agent.getEmail(), UserType.AGENT, tenantId);
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ---------------------------------------------------------------- agents

    /** Index 0 = Liam (FLIGHT, HOTEL), 1 = Emma (HOTEL, TRANSFER), 2 = Priya (VISA, TRANSFER) -
     *  deliberately overlapping so a service of one type is claimable by more than one agent,
     *  and every agent has at least one type nobody else on the demo roster manages. */
    private List<Agent> ensureAgents(String tenantId) {
        Agent liam = agentRepository.findByEmailIgnoreCase(LIAM_EMAIL)
                .orElseThrow(() -> new IllegalStateException("Expected demo agent Liam Smith to already be seeded"));
        if (liam.getManageableServices().isEmpty()) {
            liam.setManageableServices(List.of(ServiceType.FLIGHT, ServiceType.HOTEL));
            agentRepository.save(liam);
        }
        Agent emma = ensureAgent(tenantId, "Emma Wilson", "emma.wilson.demo@globalexplorer.com",
                "+1 555 234 5678", AgentDepartment.OPERATIONS, List.of(ServiceType.HOTEL, ServiceType.TRANSFER));
        Agent priya = ensureAgent(tenantId, "Priya Sharma", "priya.sharma.demo@globalexplorer.com",
                "+91 98765 43210", AgentDepartment.VISA, List.of(ServiceType.VISA, ServiceType.TRANSFER));
        return List.of(liam, emma, priya);
    }

    private Agent ensureAgent(String tenantId, String name, String email, String phone, AgentDepartment department,
                               List<ServiceType> manageableServices) {
        return agentRepository.findByEmailIgnoreCase(email).orElseGet(() -> {
            Agent agent = Agent.builder()
                    .id(UniqueIdResolver.resolve(agentRepository::existsById))
                    .tenantId(tenantId)
                    .name(name)
                    .email(email)
                    .phone(phone)
                    .department(department)
                    .manageableServices(manageableServices)
                    .password(passwordEncoder.encode(DEMO_PASSWORD))
                    .isActive(true)
                    .build();
            agentRepository.save(agent);
            log.info("Seeded demo agent '{}' (agentId={}): email={}", name, agent.getId(), email);
            return agent;
        });
    }

    // --------------------------------------------------------------- clients

    private record ClientSeed(String name, String identifier, String email, LocalDate dob, String gender,
            String passportNumber, LocalDate passportExpiry, ClientType type, int agentIdx, long monthsAgo,
            List<MemberSeed> members) {
    }

    private record MemberSeed(String name, MemberRelation relation, LocalDate dob, String gender,
            String passportNumber, LocalDate passportExpiry) {
    }

    /**
     * Seeds a mix that exercises both halves of the model: B2C households with a family roster,
     * and B2B group accounts whose members are colleagues rather than relatives. Two clients are
     * left with only their primary member so the "roster of one" path is covered too.
     */
    private List<String> seedClients(List<Agent> agents) {
        List<ClientSeed> seeds = List.of(
                new ClientSeed("Arjun Mehta", "9812345601", "arjun.mehta.demo@example.com",
                        LocalDate.of(1988, 3, 12), "Male", "M1122334", LocalDate.of(2031, 3, 12),
                        ClientType.B2C, 0, 5, List.of(
                                new MemberSeed("Sanya Mehta", MemberRelation.SPOUSE, LocalDate.of(1990, 8, 4),
                                        "Female", "M1122335", LocalDate.of(2031, 8, 4)),
                                new MemberSeed("Ira Mehta", MemberRelation.DAUGHTER, LocalDate.now().minusYears(6),
                                        "Female", "M1122336", LocalDate.of(2030, 1, 15)),
                                new MemberSeed("Veer Mehta", MemberRelation.SON, LocalDate.now().minusYears(1),
                                        "Male", null, null))),
                new ClientSeed("Priya Nair", "9812345602", "priya.nair.demo@example.com",
                        LocalDate.of(1990, 7, 22), "Female", "N2233445", LocalDate.of(2030, 7, 22),
                        ClientType.B2C, 1, 4, List.of(
                                new MemberSeed("Rohit Nair", MemberRelation.SPOUSE, LocalDate.of(1987, 2, 11),
                                        "Male", "N2233446", LocalDate.of(2029, 2, 11)))),
                new ClientSeed("Rahul Verma", "9812345603", "rahul.verma.demo@example.com",
                        LocalDate.of(1985, 11, 2), "Male", null, null,
                        ClientType.B2C, 2, 1, List.of()),
                new ClientSeed("Iyer Corporate Travel", "IYER-CORP", "ananya.iyer.demo@example.com",
                        LocalDate.of(1993, 1, 18), "Female", "I3344556", LocalDate.of(2029, 1, 18),
                        ClientType.B2B, 0, 3, List.of(
                                new MemberSeed("Nikhil Raman", MemberRelation.COLLEAGUE, LocalDate.of(1989, 5, 30),
                                        "Male", "I3344557", LocalDate.of(2030, 5, 30)),
                                new MemberSeed("Divya Menon", MemberRelation.COLLEAGUE, LocalDate.of(1992, 9, 8),
                                        "Female", "I3344558", LocalDate.of(2031, 9, 8)))),
                new ClientSeed("Vikram Malhotra", "9812345605", "vikram.malhotra.demo@example.com",
                        LocalDate.of(1979, 6, 9), "Male", "V4455667", LocalDate.of(2028, 6, 9),
                        ClientType.B2C, 1, 2, List.of(
                                new MemberSeed("Anita Malhotra", MemberRelation.SPOUSE, LocalDate.of(1982, 3, 19),
                                        "Female", "V4455668", LocalDate.of(2028, 3, 19)))),
                new ClientSeed("Kavya Reddy", "9812345606", "kavya.reddy.demo@example.com",
                        LocalDate.of(1995, 9, 30), "Female", "K5566778", LocalDate.of(2033, 9, 30),
                        ClientType.B2C, 2, 0, List.of()),
                new ClientSeed("Kapoor Friends Group", "WA-KAPOOR-GOA", "rohan.kapoor.demo@example.com",
                        LocalDate.of(1991, 12, 5), "Male", "P7788991", LocalDate.of(2032, 12, 5),
                        ClientType.B2B, 0, 1, List.of(
                                new MemberSeed("Dev Anand", MemberRelation.FRIEND, LocalDate.of(1990, 4, 2),
                                        "Male", "P7788992", LocalDate.of(2031, 4, 2)),
                                new MemberSeed("Tara Sethi", MemberRelation.FRIEND, LocalDate.of(1993, 10, 27),
                                        "Female", null, null),
                                new MemberSeed("Manav Bhatia", MemberRelation.FRIEND, LocalDate.of(1989, 7, 14),
                                        "Male", "P7788993", LocalDate.of(2029, 7, 14)))),
                new ClientSeed("Sneha Joshi", "9812345608", "sneha.joshi.demo@example.com",
                        LocalDate.of(1987, 4, 25), "Female", "S6677889", LocalDate.of(2030, 4, 25),
                        ClientType.B2C, 1, 3, List.of(
                                new MemberSeed("Aarav Joshi", MemberRelation.SON, LocalDate.now().minusYears(11),
                                        "Male", "S6677890", LocalDate.of(2032, 4, 25))))
        );

        return seeds.stream().map(s -> {
            ClientCreateRequest req = new ClientCreateRequest();
            req.setIdentifier(s.identifier());
            req.setName(s.name());
            req.setType(s.type());
            req.setPrimaryMemberName(s.name());
            req.setPrimaryMemberEmail(s.email());
            req.setPrimaryMemberCountryCode("+91");
            req.setPrimaryMemberPhone(s.identifier().matches("\\d+") ? s.identifier() : "9800000000");
            req.setPrimaryMemberDob(s.dob());
            req.setPrimaryMemberGender(s.gender());
            req.setPrimaryMemberNationality("Indian");
            req.setPrimaryMemberPassportNumber(s.passportNumber());
            req.setPrimaryMemberPassportExpiry(s.passportExpiry());
            String id = clientService.createClient(req).getId();

            for (MemberSeed m : s.members()) {
                MemberCreateRequest memberReq = new MemberCreateRequest();
                memberReq.setName(m.name());
                memberReq.setRelation(m.relation());
                memberReq.setDob(m.dob());
                memberReq.setGender(m.gender());
                memberReq.setNationality("Indian");
                memberReq.setPassportNumber(m.passportNumber());
                memberReq.setPassportExpiry(m.passportExpiry());
                memberService.addMember(id, memberReq);
            }

            backdateClient(id, s.monthsAgo());
            return id;
        }).toList();
    }

    private void backdateClient(String id, long monthsAgo) {
        Client c = clientRepository.findById(id).orElseThrow();
        c.setCreatedAt(LocalDateTime.now().minusMonths(monthsAgo).minusDays(3));
        clientRepository.save(c);
    }

    // ----------------------------------------------------------------- leads

    private record LeadSeed(int clientIdx, String destination, List<String> categories,
            String budget, LeadStatus status, String source, LeadPriority priority, int agentIdx,
            long followUpDays, String lostReason, int adults, List<Integer> kidAges, boolean seedManifest) {
    }

    /**
     * Every lead now hangs off a client, so the seed reuses the eight seeded clients rather than
     * inventing loose contact rows. Three leads get a populated traveller manifest - a family, a
     * group, and one with a dropped traveller - so the manifest, checklist roll-up and drop
     * history all have something real to render.
     */
    private List<String> seedLeads(List<Agent> agents, List<String> clientIds, String tenantId) {
        List<LeadSeed> seeds = List.of(
                new LeadSeed(2, "Bali, Indonesia",
                        List.of("HOLIDAY_PACKAGE", "HOTEL"), "1,20,000 - 1,50,000",
                        LeadStatus.NEW, "Website", LeadPriority.MEDIUM, 0, 5, null, 2, List.of(), false),
                new LeadSeed(1, "Paris, France",
                        List.of("FLIGHT", "HOTEL"), "2,00,000 - 2,50,000",
                        LeadStatus.CONTACTED, "Referral", LeadPriority.HIGH, 1, 2, null, 2, List.of(), true),
                new LeadSeed(5, "Singapore",
                        List.of("HOLIDAY_PACKAGE"), "90,000 - 1,10,000",
                        LeadStatus.QUALIFIED, "Social Media", LeadPriority.MEDIUM, 2, 7, null, 1, List.of(), false),
                new LeadSeed(0, "Bangkok, Thailand",
                        List.of("FLIGHT", "HOTEL", "VISA"), "1,50,000 - 1,80,000",
                        LeadStatus.PROPOSAL_SENT, "Walk-in", LeadPriority.HIGH, 0, -2, null,
                        2, List.of(6, 1), true),
                new LeadSeed(1, "Maldives",
                        List.of("HOLIDAY_PACKAGE", "HOTEL"), "3,00,000 - 3,50,000",
                        LeadStatus.NEGOTIATING, "WhatsApp", LeadPriority.HIGH, 1, 1, null, 2, List.of(), false),
                new LeadSeed(3, "London, UK",
                        List.of("FLIGHT", "HOTEL", "VISA"), "4,50,000",
                        LeadStatus.BOOKED, "Phone Call", LeadPriority.HIGH, 2, 30, null, 3, List.of(), true),
                new LeadSeed(4, "Switzerland",
                        List.of("HOLIDAY_PACKAGE"), "5,00,000",
                        LeadStatus.LOST, "Website", LeadPriority.MEDIUM, 0, 10,
                        "Booked with a competitor agency", 2, List.of(), false),
                new LeadSeed(7, "Goa, India",
                        List.of("HOTEL"), "40,000 - 60,000",
                        LeadStatus.NEW, "Walk-in", LeadPriority.LOW, 1, 10, null, 1, List.of(11), false),
                new LeadSeed(5, "Kerala, India",
                        List.of("HOLIDAY_PACKAGE", "HOTEL"), "80,000 - 1,00,000",
                        LeadStatus.CONTACTED, "Social Media", LeadPriority.MEDIUM, 2, -1, null, 2, List.of(), false),
                new LeadSeed(0, "Tokyo, Japan",
                        List.of("FLIGHT", "HOTEL", "VISA"), "2,80,000",
                        LeadStatus.QUALIFIED, "Referral", LeadPriority.HIGH, 0, 4, null, 2, List.of(), false),
                new LeadSeed(3, "New York, USA",
                        List.of("FLIGHT", "VISA"), "3,20,000",
                        LeadStatus.PROPOSAL_SENT, "Corporate Direct", LeadPriority.MEDIUM, 1, -3, null, 3, List.of(), false),
                new LeadSeed(6, "Dubai, UAE",
                        List.of("HOLIDAY_PACKAGE", "HOTEL"), "1,60,000 - 1,90,000",
                        LeadStatus.NEGOTIATING, "Phone Call", LeadPriority.MEDIUM, 2, 6, null, 4, List.of(), true)
        );

        List<String> leadIds = new java.util.ArrayList<>();
        for (LeadSeed s : seeds) {
            LeadCreateRequest req = new LeadCreateRequest();
            req.setClientId(clientIds.get(s.clientIdx()));
            req.setDestination(s.destination());
            req.setTravelDateFrom(LocalDate.now().plusDays(45));
            req.setTravelDateTo(LocalDate.now().plusDays(52));
            req.setCategories(s.categories());
            req.setBudget(s.budget());
            req.setFollowUpDate(LocalDate.now().plusDays(s.followUpDays()));
            req.setSource(s.source());
            req.setPriority(s.priority());
            req.setGuestDetails(GuestDetails.builder()
                    .adults(s.adults()).kids(s.kidAges().size()).kidAges(s.kidAges()).build());
            req.setLeadDescription("Enquiry for " + s.destination());

            // Owns (createdBy) whichever agent the seed names, so the new access model has
            // real variety to test against - createLead() has no explicit "on behalf of"
            // field any more, it always reads the current principal.
            setAgentSecurityContext(agents.get(s.agentIdx()), tenantId);
            LeadDetailResponse created = leadService.createLead(req);
            setOwnerSecurityContext(tenantId);

            if (s.seedManifest()) {
                seedManifest(created.getId(), clientIds.get(s.clientIdx()), s.destination());
            }

            if (s.status() != LeadStatus.NEW) {
                LeadStatusUpdateRequest statusReq = new LeadStatusUpdateRequest();
                statusReq.setStatus(s.status());
                if (s.status() == LeadStatus.LOST) {
                    statusReq.setLostReason(s.lostReason());
                }
                leadService.updateStatus(created.getId(), statusReq);
            }

            // A little texture on the two leads carrying a live proposal.
            if (s.destination().startsWith("Bangkok") || s.destination().startsWith("Maldives")) {
                addProposalItems(created.getId());
            }

            leadIds.add(created.getId());
        }
        return leadIds;
    }

    /**
     * Attaches the whole of the client's roster to the lead, confirms everyone, and - on the
     * larger groups - drops the last traveller with a reason so the DROPPED path and its
     * preserved checklist are visible in the demo data rather than only in tests.
     */
    private void seedManifest(String leadId, String clientId, String destination) {
        List<String> memberIds = memberService.listMembers(clientId).stream()
                .map(m -> m.getMemberId())
                .toList();
        if (memberIds.isEmpty()) {
            return;
        }
        LeadMemberAddRequest addReq = new LeadMemberAddRequest();
        addReq.setMemberIds(memberIds);
        leadService.addMembers(leadId, addReq);

        for (int i = 0; i < memberIds.size(); i++) {
            LeadMemberUpdateRequest update = new LeadMemberUpdateRequest();
            boolean dropLast = memberIds.size() >= 3 && i == memberIds.size() - 1;
            if (dropLast) {
                update.setStatus(LeadMemberStatus.DROPPED);
                update.setDroppedReason("Could not get leave approved for the " + destination + " dates");
            } else {
                update.setStatus(LeadMemberStatus.CONFIRMED);
            }
            leadService.updateMember(leadId, memberIds.get(i), update);
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

    // ------------------------------------------------------ services & follow-ups

    /**
     * Populates lead_service and lead_follow_up on a handful of the leads just created, with a
     * deliberate mix: an unassigned service of a type someone other than the lead's creator
     * manages (the claimable-by-a-different-agent case the whole access model exists for), a
     * service self-accepted by the agent who created the lead, and one already CONFIRMED (not
     * claimable). Without this the lead_service table is empty on every fresh demo tenant, so
     * there is nothing to browse, accept, or see on My Desk.
     *
     * leadIds is in the same order as the LeadSeed list in {@link #seedLeads}: index 1 is Paris
     * (Emma), 3 is Bangkok (Liam), 5 is London (Priya, BOOKED), 11 is Dubai (Priya).
     */
    private void seedServicesAndFollowUps(List<Agent> agents, List<String> leadIds, String tenantId) {
        Agent liam = agents.get(0);
        Agent emma = agents.get(1);
        Agent priya = agents.get(2);

        String parisLeadId = leadIds.get(1);
        String bangkokLeadId = leadIds.get(3);
        String londonLeadId = leadIds.get(5);
        String dubaiLeadId = leadIds.get(11);

        // Paris (created by Emma): a HOTEL service Emma accepts herself, and an unassigned
        // FLIGHT service that only Liam - who did not create this lead - can claim.
        var parisServices = serviceInstanceService.createServices(parisLeadId, List.of(
                hotelDraft("Paris", LocalDate.now().plusDays(45), LocalDate.now().plusDays(50)),
                flightDraft()));
        acceptAsAgent(emma, tenantId, parisLeadId, parisServices.get(0).getId());

        // Bangkok (created by Liam): an unassigned FLIGHT service (Liam's own type, claimable by
        // him), and a VISA service the owner hands straight to Priya - a cross-agent assignment
        // on a lead she did not create.
        var bangkokServices = serviceInstanceService.createServices(bangkokLeadId, List.of(
                flightDraft(),
                visaDraft("Thailand")));
        ServiceAssignRequest assignToPriya = new ServiceAssignRequest();
        assignToPriya.setAgentId(priya.getId());
        serviceInstanceService.assignService(bangkokLeadId, bangkokServices.get(1).getId(), assignToPriya);

        // London (created by Priya, already BOOKED): a VISA service she accepts and takes all
        // the way to CONFIRMED - the "not claimable, already done" case.
        var londonServices = serviceInstanceService.createServices(londonLeadId, List.of(visaDraft("United Kingdom")));
        acceptAsAgent(priya, tenantId, londonLeadId, londonServices.get(0).getId());
        ServiceStatusUpdateRequest confirm = new ServiceStatusUpdateRequest();
        confirm.setStatus(ServiceStatus.CONFIRMED);
        serviceInstanceService.setStatus(londonLeadId, londonServices.get(0).getId(), confirm);

        // Dubai (created by Priya): an unassigned TRANSFER service only Emma - who did not
        // create this lead either - can claim.
        serviceInstanceService.createServices(dubaiLeadId, List.of(transferDraft()));

        // Follow-ups: one across agents (Liam's lead, chased by Priya - visa work is hers
        // whichever lead it is on), one the creating agent owns themselves.
        addFollowUp(bangkokLeadId, LocalDate.now().plusDays(4), "Chase the Thai embassy for the visa appointment",
                priya.getId(), ServiceType.VISA);
        addFollowUp(parisLeadId, LocalDate.now().plusDays(2), "Confirm hotel booking reference with Emma",
                emma.getId(), ServiceType.HOTEL);
        addFollowUp(bangkokLeadId, LocalDate.now().plusDays(1), "Call back with the finalised flight quote",
                liam.getId(), null);
    }

    private ServiceDraft flightDraft() {
        ServiceDraft draft = new ServiceDraft();
        draft.setType(ServiceType.FLIGHT);
        return draft;
    }

    private ServiceDraft hotelDraft(String city, LocalDate checkIn, LocalDate checkOut) {
        ServiceDraft draft = new ServiceDraft();
        draft.setType(ServiceType.HOTEL);
        draft.setHotelCity(city);
        draft.setHotelCheckIn(checkIn);
        draft.setHotelCheckOut(checkOut);
        draft.setHotelRooms(1);
        return draft;
    }

    private ServiceDraft visaDraft(String country) {
        ServiceDraft draft = new ServiceDraft();
        draft.setType(ServiceType.VISA);
        draft.setVisaCountry(country);
        return draft;
    }

    private ServiceDraft transferDraft() {
        ServiceDraft draft = new ServiceDraft();
        draft.setType(ServiceType.TRANSFER);
        draft.setTransferVehicleType("4 Seater (Sedan)");
        draft.setTransferPickup("Airport");
        draft.setTransferDropoff("Hotel");
        return draft;
    }

    private void acceptAsAgent(Agent agent, String tenantId, String leadId, String serviceId) {
        setAgentSecurityContext(agent, tenantId);
        serviceInstanceService.acceptService(leadId, serviceId);
        setOwnerSecurityContext(tenantId);
    }

    private void addFollowUp(String leadId, LocalDate dueDate, String note, String assignedAgentId, ServiceType type) {
        FollowUpCreateRequest req = new FollowUpCreateRequest();
        req.setDueDate(dueDate);
        req.setNote(note);
        req.setAssignedAgentId(assignedAgentId);
        req.setServiceType(type);
        leadFollowUpService.addFollowUp(leadId, req);
    }

    // -------------------------------------------------------------- bookings

    private record BookingSeed(int clientIdx, BookingType type, String destination, BigDecimal netCost,
            BigDecimal sellingPrice, int agentIdx, long monthsAgo, BookingStatus targetStatus,
            PaymentStatus paymentStatus, String cancelReason) {
    }

    private void seedBookings(List<Agent> agents, List<String> clientIds) {
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
            req.setClientId(clientIds.get(s.clientIdx()));
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

    private record ClientInvoiceSeed(int clientIdx, int agentIdx, BigDecimal amount, long dueDays,
            String paymentMode, BigDecimal paymentAmount) {
    }

    private void seedInvoices(List<Agent> agents, List<String> clientIds) {
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
            req.setClientId(clientIds.get(s.clientIdx()));
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

    private record VisaSeed(int clientIdx, int agentIdx, String country, String visaType, String passportNumber,
            long applicationDaysAgo, String stage) {
    }

    private void seedVisas(List<Agent> agents, List<String> clientIds) {
        List<VisaSeed> seeds = List.of(
                new VisaSeed(0, 0, "United Arab Emirates", "Tourist", "M1122334", 3, "DOCUMENTS_PENDING"),
                new VisaSeed(1, 1, "Schengen (France)", "Tourist", "N2233445", 10, "APPOINTMENT_SCHEDULED"),
                new VisaSeed(3, 2, "Singapore", "Tourist", "I3344556", 15, "SUBMITTED"),
                new VisaSeed(5, 0, "United Kingdom", "Tourist", "K5566778", 30, "APPROVED"),
                new VisaSeed(2, 1, "United States", "Business", "R7788990", 20, "REJECTED")
        );

        for (VisaSeed s : seeds) {
            VisaCreateRequest req = new VisaCreateRequest();
            req.setClientId(clientIds.get(s.clientIdx()));
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
