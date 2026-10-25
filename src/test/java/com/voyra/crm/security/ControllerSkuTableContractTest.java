package com.voyra.crm.security;

import com.voyra.crm.controller.InvoiceDocumentController;
import com.voyra.crm.controller.OwnerAgentController;
import com.voyra.crm.controller.OwnerDashboardController;
import com.voyra.crm.controller.PaymentReceiptController;
import com.voyra.crm.controller.TaxConfigController;
import com.voyra.crm.enums.SkuRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review focus 1: a controller added later without a classification fails the build. Walks a real
 * RequestMappingHandlerMapping over every @Controller in com.voyra.crm. The controllers are registered
 * as lazy bean definitions and never instantiated, so no service needs mocking.
 */
class ControllerSkuTableContractTest {

    /** Spec §8's never-gated paths. */
    private static final List<String> NEVER_GATED_PREFIXES = List.of(
            "/api/auth", "/api/public/", "/api/reference", "/api/platform-bookings", "/api/wallet",
            "/api/flights", "/api/hotels", "/api/agency/profile", "/api/platform/");

    private static Map<Class<?>, Set<String>> patternsByController;

    @BeforeAll
    static void walkHandlerMapping() {
        GenericWebApplicationContext ctx = new GenericWebApplicationContext(new MockServletContext());
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        for (BeanDefinition bd : scanner.findCandidateComponents("com.voyra.crm")) {
            // test fixtures (e.g. GlobalExceptionHandlerFixtureController) are not shipped and live outside /api
            if (bd.getResourceDescription() != null && bd.getResourceDescription().contains("test-classes")) {
                continue;
            }
            bd.setLazyInit(true);
            ctx.registerBeanDefinition(bd.getBeanClassName(), bd);
        }
        ctx.refresh();
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(ctx);
        mapping.afterPropertiesSet();

        patternsByController = new HashMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            patternsByController.computeIfAbsent(e.getValue().getBeanType(), k -> new TreeSet<>())
                    .addAll(e.getKey().getPatternValues());
        }
        ctx.close();
    }

    @Test
    void everyHandlerBeanIsClassified() {
        Set<String> missing = patternsByController.keySet().stream()
                .filter(c -> !ControllerSkuTable.TABLE.containsKey(c))
                .map(Class::getSimpleName).collect(Collectors.toCollection(TreeSet::new));
        assertThat(missing).as("controllers missing from ControllerSkuTable").isEmpty();
        assertThat(patternsByController).hasSizeGreaterThanOrEqualTo(71);
    }

    @Test
    void tableHasNoStaleEntries() {
        assertThat(patternsByController.keySet()).containsAll(ControllerSkuTable.TABLE.keySet());
    }

    /**
     * Exactly the spec's never-gated paths, plus the owner dashboard (Starter keeps a dashboard) and staff
     * management (the seat check gates create/reactivate; an owner can always cut a login off).
     */
    @Test
    void neverGatedIsExactlyTheSpecList() {
        Set<String> expected = patternsByController.entrySet().stream()
                .filter(e -> e.getValue().stream().allMatch(p -> NEVER_GATED_PREFIXES.stream().anyMatch(p::startsWith)))
                .map(e -> e.getKey().getSimpleName())
                .collect(Collectors.toCollection(TreeSet::new));
        expected.add(OwnerDashboardController.class.getSimpleName());
        expected.add(OwnerAgentController.class.getSimpleName());
        Set<String> actual = ControllerSkuTable.TABLE.entrySet().stream()
                .filter(e -> e.getValue() == SkuRule.NEVER_GATED)
                .map(e -> e.getKey().getSimpleName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(actual).isEqualTo(expected);
    }

    /** The interceptor is registered on /api/** only - a gated route outside it would be silently open. */
    @Test
    void everyGatedRouteIsInsideTheInterceptorScope() {
        Set<String> outside = patternsByController.entrySet().stream()
                .filter(e -> ControllerSkuTable.TABLE.get(e.getKey()) != SkuRule.NEVER_GATED)
                .flatMap(e -> e.getValue().stream().filter(p -> !p.startsWith("/api/")).map(p -> e.getKey().getSimpleName() + " " + p))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(outside).isEmpty();
    }

    /** Client invoicing (invoices, receipts, and the tax config it reads) is CRM Core, per spec §5.2 "client invoices". */
    @Test
    void clientInvoicingIsCrmCore() {
        assertThat(List.of(InvoiceDocumentController.class, PaymentReceiptController.class, TaxConfigController.class))
                .allMatch(c -> ControllerSkuTable.TABLE.get(c) == SkuRule.CRM_CORE);
    }

    @Test
    void ruleOfAnUnclassifiedClassFailsClosed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ControllerSkuTable.rule(String.class))
                .isInstanceOf(IllegalStateException.class);
    }
}
