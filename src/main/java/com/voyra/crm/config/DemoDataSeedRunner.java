package com.voyra.crm.config;

import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.enums.AgentDepartment;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import com.voyra.crm.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Idempotent local-dev seed data, mirroring the mock UI's demo persona (Global Explorer
 * Travels / John Davis / Liam Smith) so the existing frontend mock can be pointed at real
 * data with minimal friction. Runs at the default seed order (0), strictly before
 * {@link com.voyra.crm.migration.TenantMigrationStartupRunner} (order 100), so the newly
 * seeded tenant's schema gets provisioned in the same startup.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(0)
public class DemoDataSeedRunner implements ApplicationRunner {

    private static final String DEMO_PASSWORD = "Passw0rd!";
    private static final String OWNER_EMAIL = "owner@globalexplorer.com";
    private static final String AGENT_EMAIL = "liam@globalexplorer.com";
    private static final String PLATFORM_ADMIN_EMAIL = "admin@travelos.com";

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final PlatformAdminRepository platformAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        seedPlatformAdmin();
        String tenantId = seedTenant();
        seedAgent(tenantId);
    }

    private void seedPlatformAdmin() {
        if (platformAdminRepository.findByEmailIgnoreCase(PLATFORM_ADMIN_EMAIL).isPresent()) {
            return;
        }
        PlatformAdmin admin = PlatformAdmin.builder()
                .id(generateUniqueId(platformAdminRepository::existsById))
                .name("Admin User")
                .email(PLATFORM_ADMIN_EMAIL)
                .password(passwordEncoder.encode(DEMO_PASSWORD))
                .isActive(true)
                .build();
        platformAdminRepository.save(admin);
        log.info("Seeded demo platform admin: email={}, password={}", PLATFORM_ADMIN_EMAIL, DEMO_PASSWORD);
    }

    private String seedTenant() {
        return tenantRepository.findByOwnerEmailIgnoreCase(OWNER_EMAIL)
                .map(Tenant::getId)
                .orElseGet(() -> {
                    Tenant tenant = Tenant.builder()
                            .id(generateUniqueId(tenantRepository::existsById))
                            .agencyName("Global Explorer Travels")
                            .ownerName("John Davis")
                            .ownerEmail(OWNER_EMAIL)
                            .password(passwordEncoder.encode(DEMO_PASSWORD))
                            .isActive(true)
                            .build();
                    tenantRepository.save(tenant);
                    log.info("Seeded demo agency '{}' (tenantId={}): owner email={}, password={}",
                            tenant.getAgencyName(), tenant.getId(), OWNER_EMAIL, DEMO_PASSWORD);
                    return tenant.getId();
                });
    }

    private void seedAgent(String tenantId) {
        if (agentRepository.findByEmailIgnoreCase(AGENT_EMAIL).isPresent()) {
            return;
        }
        Agent agent = Agent.builder()
                .id(generateUniqueId(agentRepository::existsById))
                .tenantId(tenantId)
                .name("Liam Smith")
                .email(AGENT_EMAIL)
                .phone("+1 555 123 4567")
                .department(AgentDepartment.SALES)
                .password(passwordEncoder.encode(DEMO_PASSWORD))
                .isActive(true)
                .build();
        agentRepository.save(agent);
        log.info("Seeded demo agent '{}' (agentId={}): email={}, password={}",
                agent.getName(), agent.getId(), AGENT_EMAIL, DEMO_PASSWORD);
    }

    private String generateUniqueId(java.util.function.Predicate<String> existsById) {
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            String id = IdGenerator.generate6();
            if (!existsById.test(id)) {
                return id;
            }
        }
        throw new IllegalStateException("Unable to generate unique id after " + maxAttempts + " attempts");
    }
}
