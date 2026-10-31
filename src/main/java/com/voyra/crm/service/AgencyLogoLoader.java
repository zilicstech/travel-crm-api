package com.voyra.crm.service;

import com.voyra.crm.entity.Tenant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/** The agency's logo bytes for a printed document (invoice, e-ticket); null when it has none or the file can't be read. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgencyLogoLoader {

    private final FileStorageService fileStorageService;

    public byte[] load(Tenant agency) {
        if (agency.getLogoKey() == null || agency.getLogoKey().isBlank()) return null;
        try (InputStream in = fileStorageService.retrieve(agency.getLogoKey()).getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("Agency logo not readable: key={}", agency.getLogoKey());
            return null;
        }
    }
}
