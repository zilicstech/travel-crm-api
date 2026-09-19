package com.voyra.crm.integration;

import com.voyra.crm.AbstractIntegrationTest;
import com.voyra.crm.context.TenantContext;
import com.voyra.crm.dto.AgencyCreateRequest;
import com.voyra.crm.dto.AgencyCreateResponse;
import com.voyra.crm.enums.DocumentKind;
import com.voyra.crm.service.AgencyService;
import com.voyra.crm.service.DocumentNumberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Converts the numbering epic's own acceptance criterion into an executable test: twenty
 * concurrent issues on one series yield 0001-0020 with no duplicate and no gap. Needs a real
 * Postgres row lock, which a mocked JdbcTemplate cannot exercise - see
 * {@link com.voyra.crm.service.DocumentNumberService}'s javadoc for why a raw
 * {@code UPDATE ... RETURNING} is used instead of a JPA save.
 */
class DocumentNumberServiceIT extends AbstractIntegrationTest {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^INV/(\\d{4}-\\d{2})/(\\d{4})$");

    @Autowired
    private AgencyService agencyService;
    @Autowired
    private DocumentNumberService documentNumberService;

    @Test
    void twentyConcurrentIssuesYieldNoDuplicateAndNoGap() throws Exception {
        AgencyCreateRequest request = new AgencyCreateRequest();
        request.setAgencyName("Numbering Concurrency Agency");
        request.setOwnerName("Owner Numbering");
        request.setOwnerEmail("owner-numbering@concurrency-test.com");
        request.setOwnerPassword("TestPass123!");
        AgencyCreateResponse agency = agencyService.createAgency(request);
        String tenantId = agency.getId();

        int concurrency = 20;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<String> results = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        List<Callable<Void>> tasks = java.util.stream.IntStream.range(0, concurrency)
                .<Callable<Void>>mapToObj(i -> () -> {
                    ready.countDown();
                    go.await();
                    TenantContext.setTenantId(tenantId);
                    try {
                        results.add(documentNumberService.next(DocumentKind.TAX_INVOICE, LocalDate.now()));
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        TenantContext.clear();
                    }
                    return null;
                })
                .collect(Collectors.toList());

        List<java.util.concurrent.Future<Void>> futures = tasks.stream().map(pool::submit).collect(Collectors.toList());
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        for (var future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(failures).isEmpty();
        assertThat(results).hasSize(concurrency);
        assertThat(results).doesNotHaveDuplicates();

        List<Integer> sequenceNumbers = results.stream().map(number -> {
            Matcher m = NUMBER_PATTERN.matcher(number);
            assertThat(m.matches()).as("number %s matches INV/<fy>/<seq>", number).isTrue();
            return Integer.parseInt(m.group(2));
        }).sorted().toList();

        assertThat(sequenceNumbers).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, concurrency).boxed().toList());
    }
}
