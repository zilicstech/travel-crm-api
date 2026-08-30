package com.voyra.crm.config;

import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the one SUPER_ADMIN account a deployed environment needs, from environment variables
 * rather than {@link DemoDataSeedRunner}'s hardcoded demo password - this runner is meant to
 * run in every environment including production, so it has no shared/known password and does
 * not depend on app.seed.demo-data.
 *
 * Idempotent: does nothing once an account with this email exists, so re-deploys are safe.
 * Ordered before {@link com.voyra.crm.migration.TenantMigrationStartupRunner} (100) for
 * consistency with the demo seeder, though platform admins have no tenant to provision.
 *
 * SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD are optional on purpose: an environment that
 * already has its admin seeded (or provisions it another way) should not be forced to keep
 * supplying them.
 */
@Component
@Order(0)
@Slf4j
public class PlatformAdminSeedRunner implements ApplicationRunner {

    private final PlatformAdminRepository platformAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;

    public PlatformAdminSeedRunner(
            PlatformAdminRepository platformAdminRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.seed.super-admin.email:}") String email,
            @Value("${app.seed.super-admin.password:}") String password) {
        this.platformAdminRepository = platformAdminRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.debug("SUPER_ADMIN_EMAIL/SUPER_ADMIN_PASSWORD not set - skipping platform admin seed");
            return;
        }
        if (platformAdminRepository.findByEmailIgnoreCase(email).isPresent()) {
            log.debug("Platform admin already exists for configured email - skipping seed");
            return;
        }

        PlatformAdmin admin = PlatformAdmin.builder()
                .id(UniqueIdResolver.resolve(platformAdminRepository::existsById))
                .name("Platform Admin")
                .email(email)
                .password(passwordEncoder.encode(password))
                .isActive(true)
                .build();
        platformAdminRepository.save(admin);
        log.info("Seeded platform admin from SUPER_ADMIN_EMAIL: email={}", email);
    }
}
