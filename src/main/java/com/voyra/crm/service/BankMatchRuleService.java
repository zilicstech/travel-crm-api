package com.voyra.crm.service;

import com.voyra.crm.dto.BankMatchRuleRequest;
import com.voyra.crm.dto.BankMatchRuleResponse;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.repository.BankMatchRuleRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BankMatchRuleService {

    private final BankMatchRuleRepository bankMatchRuleRepository;
    private final LedgerAccountRepository ledgerAccountRepository;

    @Transactional
    public BankMatchRuleResponse create(BankMatchRuleRequest request) {
        BankMatchRule rule = BankMatchRule.builder()
                .id(UniqueIdResolver.resolve(bankMatchRuleRepository::existsById))
                .isActive(true)
                .build();
        apply(rule, request);
        bankMatchRuleRepository.save(rule);
        return toResponse(rule);
    }

    @Transactional
    public BankMatchRuleResponse update(String id, BankMatchRuleRequest request) {
        BankMatchRule rule = find(id);
        apply(rule, request);
        bankMatchRuleRepository.save(rule);
        return toResponse(rule);
    }

    /** Soft delete - lines it already categorised keep their posting. */
    @Transactional
    public void delete(String id) {
        bankMatchRuleRepository.delete(find(id));
    }

    @Transactional(readOnly = true)
    public List<BankMatchRuleResponse> list() {
        return bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc().stream()
                .map(BankMatchRuleService::toResponse).toList();
    }

    private void apply(BankMatchRule rule, BankMatchRuleRequest request) {
        LedgerAccount target = ledgerAccountRepository.findByCode(request.getTargetAccountCode())
                .filter(a -> !Boolean.FALSE.equals(a.getIsActive()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Ledger account " + request.getTargetAccountCode() + " does not exist in the chart of accounts"));
        // A rule posts straight to its target, so a control account would drift from its sub-ledger.
        if (Boolean.TRUE.equals(target.getIsControl())) {
            throw new IllegalArgumentException("Account " + target.getCode() + " (" + target.getName()
                    + ") is a control account and cannot be a rule's target");
        }
        Integer priority = request.getPriority() != null ? request.getPriority() : 100;
        if (priority < 0) {
            throw new IllegalArgumentException("Priority must be zero or more (lower runs first)");
        }
        rule.setName(request.getName());
        rule.setMatchField(request.getMatchField());
        rule.setPattern(request.getPattern());
        rule.setTargetAccountCode(target.getCode());
        rule.setAutoPost(request.getAutoPost() != null ? request.getAutoPost() : false);
        rule.setPriority(priority);
    }

    private BankMatchRule find(String id) {
        return bankMatchRuleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Rule not found: " + id));
    }

    private static BankMatchRuleResponse toResponse(BankMatchRule r) {
        return BankMatchRuleResponse.builder()
                .id(r.getId()).name(r.getName()).matchField(r.getMatchField()).pattern(r.getPattern())
                .targetAccountCode(r.getTargetAccountCode()).autoPost(r.getAutoPost())
                .priority(r.getPriority()).isActive(r.getIsActive())
                .build();
    }
}
