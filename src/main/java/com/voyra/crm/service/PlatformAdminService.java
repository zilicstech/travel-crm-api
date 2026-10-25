package com.voyra.crm.service;

import com.voyra.crm.cache.PlatformAdminCache;
import com.voyra.crm.dto.PlatformAdminCreateRequest;
import com.voyra.crm.dto.PlatformAdminResponse;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.enums.PlatformAuditAction;
import com.voyra.crm.exception.NotFoundException;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.security.AesPasswordEncoder;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * Platform admins (SUPER_ADMIN logins, the platform_admin table the seed runner fills): add one with a generated password
 * shown once (stored reversibly per blueprint 4.7, like every login), deactivate, reactivate. At least one stays active.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private static final String ENTITY_TYPE = "PLATFORM_ADMIN";
    /** No 0/O, 1/l/I: the password is read off a screen and typed in. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PlatformAdminRepository repository;
    private final AesPasswordEncoder passwordEncoder;
    private final LoginEmailService loginEmailService;
    private final PlatformAuditService auditService;

    @Transactional(readOnly = true)
    public List<PlatformAdminResponse> list() {
        return repository.findAll(Sort.by("name")).stream().map(a -> toResponse(a, null)).toList();
    }

    @Transactional
    public PlatformAdminResponse create(PlatformAdminCreateRequest request) {
        String email = request.getEmail().trim();
        loginEmailService.assertFree(email);
        String password = generatePassword();
        PlatformAdmin admin = repository.save(PlatformAdmin.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .name(request.getName().trim())
                .email(email)
                .password(passwordEncoder.encode(password))
                .isActive(true)
                .build());
        auditService.record(PlatformAuditAction.PLATFORM_ADMIN_CREATE, ENTITY_TYPE, admin.getId(), Map.of("email", email));
        PlatformAdminCache.put(admin.getId(), true);
        log.info("Platform admin added: id={}", admin.getId());
        return toResponse(admin, password);
    }

    @Transactional
    public PlatformAdminResponse deactivate(String id) {
        List<PlatformAdmin> active = repository.findActiveForUpdate();
        PlatformAdmin admin = active.stream().filter(a -> a.getId().equals(id)).findFirst().orElse(null);
        if (admin == null) {
            return toResponse(find(id), null); // already inactive: nothing to do
        }
        if (active.size() <= 1) {
            throw new IllegalStateException("This is the only active platform admin - add another admin before deactivating it");
        }
        admin.setIsActive(false);
        repository.save(admin);
        auditService.record(PlatformAuditAction.PLATFORM_ADMIN_DEACTIVATE, ENTITY_TYPE, id, Map.of("email", admin.getEmail()));
        PlatformAdminCache.put(id, false);
        log.info("Platform admin deactivated: id={}", id);
        return toResponse(admin, null);
    }

    @Transactional
    public PlatformAdminResponse reactivate(String id) {
        PlatformAdmin admin = find(id);
        if (!Boolean.TRUE.equals(admin.getIsActive())) {
            admin.setIsActive(true);
            repository.save(admin);
            auditService.record(PlatformAuditAction.PLATFORM_ADMIN_REACTIVATE, ENTITY_TYPE, id, Map.of("email", admin.getEmail()));
            PlatformAdminCache.put(id, true);
            log.info("Platform admin reactivated: id={}", id);
        }
        return toResponse(admin, null);
    }

    private PlatformAdmin find(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Platform admin not found"));
    }

    static String generatePassword() {
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static PlatformAdminResponse toResponse(PlatformAdmin a, String password) {
        return PlatformAdminResponse.builder()
                .id(a.getId())
                .name(a.getName())
                .email(a.getEmail())
                .active(Boolean.TRUE.equals(a.getIsActive()))
                .createdDate(a.getCreatedDate())
                .password(password)
                .build();
    }
}
