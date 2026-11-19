package com.voyra.crm.entity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.JdbcTypeCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hibernate keys a mapped Java type once: the same type (e.g. List&lt;Integer&gt;) mapped as JSON on one entity and as an
 * array on another gets whichever it met first, which differs between the IDE run and the packaged jar. That broke every
 * lead insert in production (2026-11-19). So one Java type has one JDBC type code across all entities.
 */
class EntityTypeMappingTest {

    @Test
    void eachJavaTypeIsMappedOneWayAcrossEntities() throws Exception {
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false);
        scan.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Map<String, Integer> codes = new HashMap<>();
        Map<String, String> firstSeen = new HashMap<>();
        for (var bean : scan.findCandidateComponents("com.voyra.crm.entity")) {
            for (Field f : Class.forName(bean.getBeanClassName()).getDeclaredFields()) {
                JdbcTypeCode code = f.getAnnotation(JdbcTypeCode.class);
                if (code == null) continue;
                String type = f.getGenericType().getTypeName();
                String here = f.getDeclaringClass().getSimpleName() + "." + f.getName();
                Integer before = codes.putIfAbsent(type, code.value());
                firstSeen.putIfAbsent(type, here);
                assertThat(before == null || before == code.value())
                        .as("%s is mapped with JDBC type %s at %s but %s at %s", type, code.value(), here, before, firstSeen.get(type))
                        .isTrue();
            }
        }
        assertThat(codes).isNotEmpty();
    }
}
