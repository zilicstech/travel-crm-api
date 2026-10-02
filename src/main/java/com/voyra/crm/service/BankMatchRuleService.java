package com.voyra.crm.service;

import com.voyra.crm.dto.BankMatchRuleRequest;
import com.voyra.crm.dto.BankMatchRuleResponse;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.repository.BankMatchRuleRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BankMatchRuleService {

    private final BankMatchRuleRepository bankMatchRuleRepository;

    @Transactional
    public BankMatchRuleResponse create(BankMatchRuleRequest request) {
        BankMatchRule rule = BankMatchRule.builder()
                .id(UniqueIdResolver.resolve(bankMatchRuleRepository::existsById))
                .name(request.getName())
                .matchField(request.getMatchField())
                .pattern(request.getPattern())
                .targetAccountCode(request.getTargetAccountCode())
                .autoPost(request.getAutoPost() != null ? request.getAutoPost() : false)
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .isActive(true)
                .build();
        bankMatchRuleRepository.save(rule);
        return toResponse(rule);
    }

    @Transactional(readOnly = true)
    public List<BankMatchRuleResponse> list() {
        return bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc().stream()
                .map(BankMatchRuleService::toResponse).toList();
    }

    private static BankMatchRuleResponse toResponse(BankMatchRule r) {
        return BankMatchRuleResponse.builder()
                .id(r.getId()).name(r.getName()).matchField(r.getMatchField()).pattern(r.getPattern())
                .targetAccountCode(r.getTargetAccountCode()).autoPost(r.getAutoPost())
                .priority(r.getPriority()).isActive(r.getIsActive())
                .build();
    }
}
