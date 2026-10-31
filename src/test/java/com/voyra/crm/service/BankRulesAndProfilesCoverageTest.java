package com.voyra.crm.service;

import com.voyra.crm.dto.BankMatchRuleRequest;
import com.voyra.crm.dto.BankMatchRuleResponse;
import com.voyra.crm.dto.BankStatementProfileRequest;
import com.voyra.crm.dto.BankStatementProfileResponse;
import com.voyra.crm.entity.BankMatchRule;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.entity.LedgerAccount;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankMatchField;
import com.voyra.crm.enums.LedgerAccountType;
import com.voyra.crm.repository.BankMatchRuleRepository;
import com.voyra.crm.repository.BankStatementProfileRepository;
import com.voyra.crm.repository.LedgerAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BankMatchRuleService and BankStatementProfileService: the create, list and refusal paths BankingSetupServiceTest leaves out. */
@ExtendWith(MockitoExtension.class)
class BankRulesAndProfilesCoverageTest {

    @Mock private BankMatchRuleRepository bankMatchRuleRepository;
    @Mock private LedgerAccountRepository ledgerAccountRepository;
    @Mock private BankStatementProfileRepository bankStatementProfileRepository;

    private BankMatchRuleService rules() {
        return new BankMatchRuleService(bankMatchRuleRepository, ledgerAccountRepository);
    }

    private BankStatementProfileService profiles() {
        return new BankStatementProfileService(bankStatementProfileRepository);
    }

    private static BankMatchRuleRequest rule(String target) {
        BankMatchRuleRequest r = new BankMatchRuleRequest();
        r.setName("Card fees");
        r.setMatchField(BankMatchField.DESCRIPTION);
        r.setPattern("MDR");
        r.setTargetAccountCode(target);
        return r;
    }

    @Test
    void aNewRuleDefaultsToSuggestOnlyAtPriorityOneHundred() {
        when(ledgerAccountRepository.findByCode("5620")).thenReturn(Optional.of(LedgerAccount.builder().code("5620").name("Bank charges")
                .accountType(LedgerAccountType.EXPENSE).isControl(null).isActive(null).build()));

        BankMatchRuleResponse r = rules().create(rule("5620"));

        assertThat(r.getId()).isNotBlank();
        assertThat(r.getAutoPost()).isFalse();
        assertThat(r.getPriority()).isEqualTo(100);
        assertThat(r.getIsActive()).isTrue();
        assertThat(r.getTargetAccountCode()).isEqualTo("5620");
        verify(bankMatchRuleRepository).save(any(BankMatchRule.class));
    }

    @Test
    void anAutoPostRuleKeepsItsPriority() {
        when(ledgerAccountRepository.findByCode("5620")).thenReturn(Optional.of(LedgerAccount.builder().code("5620").isControl(false).isActive(true).build()));
        BankMatchRuleRequest req = rule("5620");
        req.setAutoPost(true);
        req.setPriority(0);

        BankMatchRuleResponse r = rules().create(req);

        assertThat(r.getAutoPost()).isTrue();
        assertThat(r.getPriority()).isZero();
    }

    @Test
    void anInactiveTargetAccountIsTreatedAsMissing() {
        when(ledgerAccountRepository.findByCode("5620")).thenReturn(Optional.of(LedgerAccount.builder().code("5620").isActive(false).build()));
        assertThatThrownBy(() -> rules().create(rule("5620")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Ledger account 5620 does not exist in the chart of accounts");
        verify(bankMatchRuleRepository, never()).save(any());
    }

    @Test
    void activeRulesAreListedInPriorityOrderAndAnUnknownOneIsNotFound() {
        when(bankMatchRuleRepository.findByIsActiveTrueOrderByPriorityAsc()).thenReturn(List.of(
                BankMatchRule.builder().id("R1").name("Fees").priority(1).build()));
        assertThat(rules().list()).extracting(BankMatchRuleResponse::getId).containsExactly("R1");
        assertThatThrownBy(() -> rules().delete("R9")).isInstanceOf(IllegalArgumentException.class).hasMessage("Rule not found: R9");
    }

    private static BankStatementProfileRequest profile(String fileFormat, String columnMap) {
        BankStatementProfileRequest r = new BankStatementProfileRequest();
        r.setBankName("HDFC");
        r.setFileFormat(fileFormat);
        r.setDateFormat("dd/MM/yyyy");
        r.setAmountConvention(BankAmountConvention.SINGLE_SIGNED);
        r.setColumnMap(columnMap);
        return r;
    }

    @Test
    void aProfileDefaultsToCsvAndIsStoredUpperCase() {
        BankStatementProfileResponse created = profiles().create(profile(null, "{\"date\":\"Date\",\"amount\":\"Amount\"}"));
        BankStatementProfileResponse lower = profiles().create(profile("csv", "{\"date\":\"Date\",\"amount\":\"Amount\"}"));

        assertThat(created.getFileFormat()).isEqualTo("CSV");
        assertThat(lower.getFileFormat()).isEqualTo("CSV");
        assertThat(created.getBankName()).isEqualTo("HDFC");
        ArgumentCaptor<BankStatementProfile> saved = ArgumentCaptor.forClass(BankStatementProfile.class);
        verify(bankStatementProfileRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().getCreatedDate()).isNotNull();
    }

    @Test
    void onlyCsvAndRealColumnNamesAreAccepted() {
        assertThatThrownBy(() -> profiles().create(profile("XLSX", "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Only CSV statements can be imported");
        assertThatThrownBy(() -> profiles().create(profile("CSV", "{\"date\":\"Date\",\"amount\":7}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The column map needs the statement's \"amount\" column");
        assertThatThrownBy(() -> profiles().create(profile("CSV", "{\"date\":\" \",\"amount\":\"Amount\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The column map needs the statement's \"date\" column");
        verify(bankStatementProfileRepository, never()).save(any());
    }

    @Test
    void profilesAreListedAndAnUnknownOneIsNotFound() {
        when(bankStatementProfileRepository.findAll()).thenReturn(List.of(BankStatementProfile.builder().id("P1").bankName("HDFC").build()));
        assertThat(profiles().list()).extracting(BankStatementProfileResponse::getBankName).containsExactly("HDFC");
        assertThatThrownBy(() -> profiles().update("P9", profile("CSV", "{}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Statement profile not found: P9");
    }
}
