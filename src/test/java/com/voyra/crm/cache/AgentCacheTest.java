package com.voyra.crm.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCacheTest {

    @AfterEach
    void clear() {
        AgentCache.remove("AG1");
    }

    /** A password reset ends the sessions issued before it; a fresh login after it works. */
    @Test
    void aPasswordResetRevokesOlderTokensOnly() {
        AgentCache.put("AG1", true);
        assertThat(AgentCache.isActive("AG1", new Date(1_000_000L))).isTrue();

        AgentCache.revokeTokensBefore("AG1", 2_000L);

        assertThat(AgentCache.isActive("AG1", new Date(1_999_000L))).isFalse();
        assertThat(AgentCache.isActive("AG1", new Date(2_000_000L))).isTrue();
    }

    @Test
    void anInactiveAgentIsRefusedWhateverTheToken() {
        AgentCache.put("AG1", false);
        assertThat(AgentCache.isActive("AG1", new Date())).isFalse();
    }
}
