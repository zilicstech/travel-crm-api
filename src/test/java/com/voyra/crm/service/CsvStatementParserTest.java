package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.models.StatementParseResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule 4.2.1 - pure, no Spring context needed; exercised directly against fixture bytes. */
class CsvStatementParserTest {

    private final CsvStatementParser parser = new CsvStatementParser(new ObjectMapper());

    @Test
    void parsesASingleSignedAmountStatement() {
        BankStatementProfile profile = BankStatementProfile.builder()
                .dateFormat("dd/MM/yyyy")
                .amountConvention(BankAmountConvention.SINGLE_SIGNED)
                .columnMap("{\"date\":\"Date\",\"description\":\"Narration\",\"reference\":\"Ref\",\"amount\":\"Amount\"}")
                .build();
        String csv = "Date,Narration,Ref,Amount\n"
                + "20/09/2026,NEFT FROM RAKESH VERMA,UTR12345,42000.00\n"
                + "21/09/2026,WIRE FEE,,-500.00\n";

        StatementParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), profile);

        assertThat(result.rowErrors()).isEmpty();
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).txnDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        assertThat(result.lines().get(0).amount()).isEqualByComparingTo("42000.00");
        assertThat(result.lines().get(0).direction()).isEqualTo(BankTransactionDirection.CREDIT);
        assertThat(result.lines().get(0).bankReference()).isEqualTo("UTR12345");
        assertThat(result.lines().get(1).amount()).isEqualByComparingTo("500.00");
        assertThat(result.lines().get(1).direction()).isEqualTo(BankTransactionDirection.DEBIT);
    }

    @Test
    void parsesADebitCreditColumnStatement() {
        BankStatementProfile profile = BankStatementProfile.builder()
                .dateFormat("dd/MM/yyyy")
                .amountConvention(BankAmountConvention.DEBIT_CREDIT_COLUMNS)
                .columnMap("{\"date\":\"Date\",\"description\":\"Narration\",\"debit\":\"Debit\",\"credit\":\"Credit\"}")
                .build();
        String csv = "Date,Narration,Debit,Credit\n"
                + "20/09/2026,PAYMENT TO IndiGo,13020.00,\n"
                + "21/09/2026,RECEIPT FROM CLIENT,,42000.00\n";

        StatementParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), profile);

        assertThat(result.rowErrors()).isEmpty();
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).amount()).isEqualByComparingTo("13020.00");
        assertThat(result.lines().get(0).direction()).isEqualTo(BankTransactionDirection.DEBIT);
        assertThat(result.lines().get(1).amount()).isEqualByComparingTo("42000.00");
        assertThat(result.lines().get(1).direction()).isEqualTo(BankTransactionDirection.CREDIT);
    }

    @Test
    void reportsAMalformedRowInsteadOfSilentlyDroppingIt() {
        BankStatementProfile profile = BankStatementProfile.builder()
                .dateFormat("dd/MM/yyyy")
                .amountConvention(BankAmountConvention.SINGLE_SIGNED)
                .columnMap("{\"date\":\"Date\",\"amount\":\"Amount\"}")
                .build();
        String csv = "Date,Amount\n"
                + "20/09/2026,1000.00\n"
                + "not-a-date,500.00\n"
                + "22/09/2026,2000.00\n";

        StatementParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), profile);

        assertThat(result.lines()).hasSize(2); // the two valid rows still parse
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0)).contains("Row 3");
    }

    @Test
    void handlesQuotedFieldsWithEmbeddedCommas() {
        BankStatementProfile profile = BankStatementProfile.builder()
                .dateFormat("dd/MM/yyyy")
                .amountConvention(BankAmountConvention.SINGLE_SIGNED)
                .columnMap("{\"date\":\"Date\",\"description\":\"Narration\",\"amount\":\"Amount\"}")
                .build();
        String csv = "Date,Narration,Amount\n"
                + "20/09/2026,\"NEFT, FROM CLIENT\",1000.00\n";

        StatementParseResult result = parser.parse(csv.getBytes(StandardCharsets.UTF_8), profile);

        assertThat(result.rowErrors()).isEmpty();
        assertThat(result.lines().get(0).description()).isEqualTo("NEFT, FROM CLIENT");
    }
}
