package com.voyra.crm.service;

import com.voyra.crm.dto.ProposalLinkResponse;
import com.voyra.crm.entity.Lead;
import com.voyra.crm.entity.ProposalLink;
import com.voyra.crm.repository.LeadRepository;
import com.voyra.crm.repository.ProposalLinkRepository;
import com.voyra.crm.security.SecurityContextUtil;
import com.voyra.crm.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Generates the unguessable public share-link token for a lead's proposal. The token (not
 * the lead's short, enumerable id) is what the public proposal page is keyed on - see
 * PublicProposalService for the resolution side of this.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProposalLinkService {

    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final ProposalLinkRepository proposalLinkRepository;

    @Value("${app.public-proposal.base-url}")
    private String publicProposalBaseUrl;

    @Value("${app.public-proposal.validity-days:90}")
    private int validityDays;

    @Transactional
    public ProposalLinkResponse generateLink(String leadId) {
        Lead lead = leadService.findAccessibleLead(leadId);
        String tenantId = SecurityContextUtil.getCurrentUserOrThrow().tenantId();

        String token = lead.getPublicProposalToken();
        if (token == null || proposalLinkRepository.findById(token).isEmpty()) {
            token = generateUniqueToken();
            lead.setPublicProposalToken(token);
            leadRepository.save(lead);

            proposalLinkRepository.save(ProposalLink.builder()
                    .token(token)
                    .tenantId(tenantId)
                    .leadId(leadId)
                    .expiresAt(LocalDateTime.now().plusDays(validityDays))
                    .build());
            log.info("Proposal link generated: leadId={}", leadId);
        }

        return ProposalLinkResponse.builder()
                .token(token)
                .url(publicProposalBaseUrl + "/" + token)
                .build();
    }

    private String generateUniqueToken() {
        int maxAttempts = 10;
        for (int i = 0; i < maxAttempts; i++) {
            String token = IdGenerator.generateToken32();
            if (!proposalLinkRepository.existsById(token)) {
                return token;
            }
        }
        throw new IllegalStateException("Unable to generate unique proposal token after " + maxAttempts + " attempts");
    }
}
