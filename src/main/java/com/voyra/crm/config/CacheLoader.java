package com.voyra.crm.config;

import com.voyra.crm.cache.AgentCache;
import com.voyra.crm.cache.PlatformAdminCache;
import com.voyra.crm.cache.TenantCache;
import com.voyra.crm.entity.Agent;
import com.voyra.crm.entity.PlatformAdmin;
import com.voyra.crm.entity.Tenant;
import com.voyra.crm.repository.AgentRepository;
import com.voyra.crm.repository.PlatformAdminRepository;
import com.voyra.crm.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Populates every static ConcurrentHashMap cache on startup, once the context is fully ready
 * (which is also after every ApplicationRunner - seeding and tenant migration - has finished).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheLoader {

    private final TenantRepository tenantRepository;
    private final AgentRepository agentRepository;
    private final PlatformAdminRepository platformAdminRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void loadCaches() {
        List<Tenant> tenants = tenantRepository.findAll();
        TenantCache.loadFrom(tenants.stream()
                .map(t -> new TenantCache.TenantEntry(t.getId(), t.getAgencyName(), Boolean.TRUE.equals(t.getIsActive())))
                .toList());

        List<Agent> agents = agentRepository.findAll();
        AgentCache.loadFrom(agents.stream()
                .map(a -> new AgentCache.AgentEntry(a.getId(), Boolean.TRUE.equals(a.getIsActive())))
                .toList());

        List<PlatformAdmin> admins = platformAdminRepository.findAll();
        PlatformAdminCache.loadFrom(admins.stream()
                .map(p -> new PlatformAdminCache.AdminEntry(p.getId(), Boolean.TRUE.equals(p.getIsActive())))
                .toList());

        log.info("Caches loaded: {} tenant(s), {} agent(s), {} platform admin(s)",
                tenants.size(), agents.size(), admins.size());
    }
}
