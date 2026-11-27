package com.voyra.crm.scheduler;

import com.voyra.crm.service.AgencyEventHub;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgencyEventHeartbeatJobTest {

    @Test
    void pingsTheOpenStreams() {
        AgencyEventHub hub = mock(AgencyEventHub.class);
        new AgencyEventHeartbeatJob(hub).run();
        verify(hub).heartbeat();
    }
}
