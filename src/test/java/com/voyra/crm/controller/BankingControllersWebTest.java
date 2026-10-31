package com.voyra.crm.controller;

import com.voyra.crm.config.SecurityConfig;
import com.voyra.crm.dto.BankAccountResponse;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankMatchField;
import com.voyra.crm.enums.BankMatchedSourceType;
import com.voyra.crm.enums.UserType;
import com.voyra.crm.security.JwtAuthenticationFilter;
import com.voyra.crm.security.JwtService;
import com.voyra.crm.security.RestAuthenticationEntryPoint;
import com.voyra.crm.service.BankAccountService;
import com.voyra.crm.service.BankMatchRuleService;
import com.voyra.crm.service.BankMatchingService;
import com.voyra.crm.service.BankStatementImportService;
import com.voyra.crm.service.BankStatementProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static com.voyra.crm.controller.WebTestSupport.FILE;
import static com.voyra.crm.controller.WebTestSupport.as;
import static com.voyra.crm.controller.WebTestSupport.json;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({BankAccountController.class, BankMatchRuleController.class, BankStatementImportController.class,
        BankStatementProfileController.class, BankTransactionController.class})
@ActiveProfiles("test")
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, RestAuthenticationEntryPoint.class})
class BankingControllersWebTest {

    private static final String BASE = "/api/accounts/banking";

    @Autowired private MockMvc mvc;
    @MockBean private BankAccountService bankAccountService;
    @MockBean private BankMatchRuleService bankMatchRuleService;
    @MockBean private BankStatementImportService bankStatementImportService;
    @MockBean private BankStatementProfileService bankStatementProfileService;
    @MockBean private BankMatchingService bankMatchingService;

    @Test
    void bankAccountsAreManagedByOwnerAndAccountant() throws Exception {
        given(bankAccountService.list()).willReturn(List.of(BankAccountResponse.builder().id("BA1").build()));
        String body = "{\"accountName\":\"Current\",\"ledgerAccountCode\":\"1100\"}";

        mvc.perform(json(post(BASE + "/accounts"), body).with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/accounts").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("BA1"));
        mvc.perform(json(put(BASE + "/accounts/BA1"), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/accounts/BA1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());

        verify(bankAccountService).create(any());
        verify(bankAccountService).update(eq("BA1"), any());
        verify(bankAccountService).delete("BA1");
    }

    @Test
    void agentsCannotTouchBanking() throws Exception {
        mvc.perform(get(BASE + "/accounts").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/match-rules").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/transactions/unmatched").param("bankAccountId", "BA1").with(as(UserType.AGENT))).andExpect(status().isForbidden());
        verifyNoInteractions(bankAccountService, bankMatchRuleService, bankMatchingService);
    }

    @Test
    void matchRulesCrud() throws Exception {
        String body = "{\"name\":\"Rent\",\"matchField\":\"" + BankMatchField.values()[0] + "\",\"pattern\":\"RENT\",\"targetAccountCode\":\"6100\"}";

        mvc.perform(json(post(BASE + "/match-rules"), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/match-rules").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(put(BASE + "/match-rules/R1"), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/match-rules/R1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());

        verify(bankMatchRuleService).create(any());
        verify(bankMatchRuleService).list();
        verify(bankMatchRuleService).update(eq("R1"), any());
        verify(bankMatchRuleService).delete("R1");
    }

    @Test
    void statementProfilesCrud() throws Exception {
        String body = "{\"bankName\":\"HDFC\",\"dateFormat\":\"dd/MM/yyyy\",\"columnMap\":\"{}\",\"amountConvention\":\""
                + BankAmountConvention.values()[0] + "\"}";

        mvc.perform(json(post(BASE + "/statement-profiles"), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/statement-profiles").with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(json(put(BASE + "/statement-profiles/P1"), body).with(as(UserType.AGENCY_OWNER))).andExpect(status().isOk());
        mvc.perform(delete(BASE + "/statement-profiles/P1").with(as(UserType.AGENCY_OWNER))).andExpect(status().isNoContent());

        verify(bankStatementProfileService).create(any());
        verify(bankStatementProfileService).list();
        verify(bankStatementProfileService).update(eq("P1"), any());
        verify(bankStatementProfileService).delete("P1");
    }

    @Test
    void anImportReportsWhatWasSavedSkippedAndMatched() throws Exception {
        given(bankStatementImportService.importStatement(eq("BA1"), eq("P1"), any()))
                .willReturn(new BankStatementImportService.BankImportOutcome("IMP1", 10, 2, 4, List.of("row 7: bad date")));

        mvc.perform(multipart(BASE + "/imports").file(FILE).param("bankAccountId", "BA1").param("profileId", "P1")
                        .with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("IMP1"))
                .andExpect(jsonPath("$.bankAccountId").value("BA1"))
                .andExpect(jsonPath("$.fileName").value("doc.pdf"))
                .andExpect(jsonPath("$.rowCount").value(10))
                .andExpect(jsonPath("$.duplicateCount").value(2))
                .andExpect(jsonPath("$.tier1Matched").value(4))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.parseErrors[0]").value("row 7: bad date"));
    }

    @Test
    void transactionsListMatchAndApplyRules() throws Exception {
        given(bankMatchingService.applyMatchRules("BA1")).willReturn(3);

        mvc.perform(get(BASE + "/transactions/unmatched").param("bankAccountId", "BA1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/transactions/tier2-candidates").param("bankAccountId", "BA1").with(as(UserType.ACCOUNTANT))).andExpect(status().isOk());
        mvc.perform(json(post(BASE + "/transactions/TX1/confirm-match"),
                        "{\"sourceType\":\"" + BankMatchedSourceType.values()[0] + "\",\"sourceId\":\"S1\"}").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isNoContent());
        mvc.perform(post(BASE + "/transactions/apply-rules").param("bankAccountId", "BA1").with(as(UserType.ACCOUNTANT)))
                .andExpect(status().isOk()).andExpect(content().string("3"));

        verify(bankMatchingService).listUnmatched("BA1");
        verify(bankMatchingService).listTier2Candidates("BA1");
        verify(bankMatchingService).confirmMatch("TX1", BankMatchedSourceType.values()[0], "S1");
    }
}
