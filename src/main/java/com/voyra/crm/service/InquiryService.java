package com.voyra.crm.service;

import com.voyra.crm.dto.InquiryCreateRequest;
import com.voyra.crm.dto.InquiryResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.Inquiry;
import com.voyra.crm.repository.InquiryRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Locale;

/** Marketing-site sales inquiries: saved from the public site, read by the Platform Admin. */
@Service
@RequiredArgsConstructor
@Slf4j
public class InquiryService {

    private final InquiryRepository inquiryRepository;

    /** @return false when the honeypot was filled - the caller still answers success so a bot learns nothing */
    @Transactional
    public boolean submit(InquiryCreateRequest request) {
        if (StringUtils.hasText(request.getWebsite())) {
            log.info("Inquiry dropped by honeypot: source={}", request.getSource());
            return false;
        }
        String email = trimToNull(request.getEmail());
        String phone = trimToNull(request.getPhone());
        if (email == null && phone == null) {
            throw new IllegalArgumentException("Email or phone is required");
        }
        inquiryRepository.save(Inquiry.builder()
                .id(UniqueIdResolver.resolve(inquiryRepository::existsById))
                .source(request.getSource().trim())
                .name(trimToNull(request.getName()))
                .email(email)
                .phone(phone)
                .company(trimToNull(request.getCompany()))
                .plan(trimToNull(request.getPlan()))
                .message(trimToNull(request.getMessage()))
                .build());
        return true;
    }

    @Transactional(readOnly = true)
    public PagedResponse<InquiryResponse> list(String search, Pageable pageable) {
        String q = trimToNull(search);
        Page<Inquiry> page = q == null
                ? inquiryRepository.findAllByOrderByCreatedAtDesc(pageable)
                : inquiryRepository.search("%" + q.toLowerCase(Locale.ROOT) + "%", pageable);
        return PagedResponse.from(page, InquiryService::toResponse);
    }

    private static InquiryResponse toResponse(Inquiry i) {
        return InquiryResponse.builder()
                .id(i.getId())
                .source(i.getSource())
                .name(i.getName())
                .email(i.getEmail())
                .phone(i.getPhone())
                .company(i.getCompany())
                .plan(i.getPlan())
                .message(i.getMessage())
                .createdAt(i.getCreatedAt())
                .build();
    }

    private static String trimToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
