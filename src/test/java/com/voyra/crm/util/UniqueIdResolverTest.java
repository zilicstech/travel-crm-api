package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UniqueIdResolverTest {

    @Test
    void returnsIdImmediatelyWhenNoCollision() {
        String id = UniqueIdResolver.resolve(existing -> false);
        assertThat(id).hasSize(36);
    }

    @Test
    void retriesPastCollidingIdsUntilOneIsFree() {
        AtomicInteger calls = new AtomicInteger(0);
        String id = UniqueIdResolver.resolve(existing -> calls.incrementAndGet() <= 3);
        assertThat(id).hasSize(36);
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void alwaysCollidingPredicateThrowsAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger(0);
        assertThatThrownBy(() -> UniqueIdResolver.resolve(existing -> {
            calls.incrementAndGet();
            return true;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(calls.get()).isEqualTo(10);
    }
}
