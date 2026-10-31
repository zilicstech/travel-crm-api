package com.voyra.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.enums.BankTransactionDirection;
import com.voyra.crm.models.StatementParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvStatementParserCoverageTest {

    private final CsvStatementParser parser = new CsvStatementParser(new ObjectMapper());

    private static BankStatementProfile profile(BankAmountConvention convention, String columnMap) {
        return BankStatementProfile.builder().dateFormat("dd/MM/yyyy").amountConvention(convention).columnMap(columnMap).build();
    }

    private StatementParseResult parse(String csv, BankStatementProfile profile) {
        return parser.parse(csv.getBytes(StandardCharsets.UTF_8), profile);
    }

    @Test
    void itParsesCsv() {
        assertThat(parser.supportedFormat()).isEqualTo("CSV");
    }

    @Test
    void anEmptyFileSaysSo() {
        StatementParseResult r = parse("", profile(BankAmountConvention.SINGLE_SIGNED, "{\"date\":\"Date\",\"amount\":\"Amount\"}"));
        assertThat(r.lines()).isEmpty();
        assertThat(r.rowErrors()).containsExactly("File is empty");
    }

    @Test
    void aProfileWhoseColumnMapIsNotJsonIsRefused() {
        assertThatThrownBy(() -> parse("Date\n", profile(BankAmountConvention.SINGLE_SIGNED, "not json")))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Statement profile's column_map is not valid JSON: ");
    }

    @Test
    void blankRowsAreSkippedAndANegativeIsACreditWhenPositiveIsDebit() {
        String map = "{\"date\":\"Date\",\"amount\":\"Amount\",\"balance\":\"Balance\",\"positiveIsCredit\":\"FALSE\"}";
        String csv = "Date,Amount,Balance\n\n01/10/2026,-250.00,1000\n02/10/2026,40,\n   \n";

        StatementParseResult r = parse(csv, profile(BankAmountConvention.SINGLE_SIGNED, map));

        assertThat(r.rowErrors()).isEmpty();
        assertThat(r.lines()).extracting(l -> l.direction()).containsExactly(BankTransactionDirection.CREDIT, BankTransactionDirection.DEBIT);
        assertThat(r.lines().get(0).amount()).isEqualByComparingTo("250.00");
        assertThat(r.lines().get(0).runningBalance()).isEqualByComparingTo("1000");
        assertThat(r.lines().get(1).runningBalance()).isEqualByComparingTo("0");
        assertThat(r.lines().get(0).description()).isNull();
        assertThat(r.lines().get(0).bankReference()).isNull();
    }

    @Test
    void eachBadRowIsReportedWithItsNumber() {
        String map = "{\"date\":\"Date\",\"debit\":\"Dr\",\"credit\":\"Cr\"}";
        String csv = "Date,Dr,Cr\n01/10/2026,10,20\n01/10/2026,,\n,5,\n01/10/2026,\"1,000\",\n";

        StatementParseResult r = parse(csv, profile(BankAmountConvention.DEBIT_CREDIT_COLUMNS, map));

        assertThat(r.rowErrors()).containsExactly(
                "Row 2: row carries both a debit and a credit amount",
                "Row 3: row carries neither a debit nor a credit amount",
                "Row 4: missing value for 'date' (column 'Date')");
        assertThat(r.lines()).singleElement().satisfies(l -> {
            assertThat(l.amount()).isEqualByComparingTo("1000");
            assertThat(l.direction()).isEqualTo(BankTransactionDirection.DEBIT);
        });
    }

    @Test
    void aMissingMappingFailsEveryRow() {
        StatementParseResult noCredit = parse("Date,Dr\n01/10/2026,10\n",
                profile(BankAmountConvention.DEBIT_CREDIT_COLUMNS, "{\"date\":\"Date\",\"debit\":\"Dr\",\"credit\":\" \"}"));
        assertThat(noCredit.rowErrors()).containsExactly("Row 2: Statement profile's column_map has no mapping for 'credit'");

        StatementParseResult noAmount = parse("Date\n01/10/2026\n", profile(BankAmountConvention.SINGLE_SIGNED, "{\"date\":\"Date\"}"));
        assertThat(noAmount.rowErrors()).containsExactly("Row 2: Statement profile's column_map has no mapping for 'amount'");
    }

    @Test
    void aDoubledQuoteInsideAQuotedFieldIsOneQuote() {
        String map = "{\"date\":\"Date\",\"description\":\"Narration\",\"amount\":\"Amount\"}";
        StatementParseResult r = parse("Date,Narration,Amount\n01/10/2026,\"Paid \"\"Hotel\"\", Goa\",100\n",
                profile(BankAmountConvention.SINGLE_SIGNED, map));

        assertThat(r.lines()).singleElement().satisfies(l -> assertThat(l.description()).isEqualTo("Paid \"Hotel\", Goa"));
    }

    @Test
    void recordsSplitOnEveryLineEndingAndKeepBreaksInsideQuotes() {
        String text = "a,\"multi\nline\",b\r\nc,\"x\r\ny\",\"q\"\"q\"\rd,\"old\rmac\"\ne";

        List<CsvStatementParser.CsvRecord> records = CsvStatementParser.records(text);

        assertThat(records).extracting(CsvStatementParser.CsvRecord::line).containsExactly(1, 3, 5, 7);
        assertThat(records.get(0).fields()).containsExactly("a", "multi\nline", "b");
        assertThat(records.get(1).fields()).containsExactly("c", "x\r\ny", "q\"q");
        assertThat(records.get(2).fields()).containsExactly("d", "old\rmac");
        assertThat(records.get(3).fields()).containsExactly("e");
    }

    @Test
    void aTextEndingInANewlineHasNoEmptyTrailingRecordAndAnOpenQuoteIsRefused() {
        assertThat(CsvStatementParser.records("a,b\n")).singleElement().satisfies(r -> assertThat(r.fields()).containsExactly("a", "b"));
        assertThat(CsvStatementParser.records("a,")).singleElement().satisfies(r -> assertThat(r.fields()).containsExactly("a", ""));
        assertThat(CsvStatementParser.records("")).isEmpty();
        assertThat(CsvStatementParser.records("a,\"b\"")).singleElement().satisfies(r -> assertThat(r.fields()).containsExactly("a", "b"));
        assertThat(CsvStatementParser.records("a\r")).singleElement().satisfies(r -> assertThat(r.fields()).containsExactly("a"));
        assertThatThrownBy(() -> CsvStatementParser.records("a\n\"open"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Line 2: a quoted field is never closed");
    }

    @Test
    void aShortRowLeavesItsMissingColumnsEmpty() {
        StatementParseResult r = parse("Date,Dr,Cr\n01/10/2026,75\n",
                profile(BankAmountConvention.DEBIT_CREDIT_COLUMNS, "{\"date\":\"Date\",\"debit\":\"Dr\",\"credit\":\"Cr\"}"));
        assertThat(r.lines()).singleElement().satisfies(l -> {
            assertThat(l.amount()).isEqualByComparingTo("75");
            assertThat(l.direction()).isEqualTo(BankTransactionDirection.DEBIT);
        });

        StatementParseResult noDate = parse("Day,Amount\n01/10/2026,5\n",
                profile(BankAmountConvention.SINGLE_SIGNED, "{\"date\":\"Date\",\"amount\":\"Amount\"}"));
        assertThat(noDate.rowErrors()).containsExactly("Row 2: missing value for 'date' (column 'Date')");
    }
}
