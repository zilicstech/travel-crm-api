package com.voyra.crm.scheduler;

import com.voyra.crm.service.AgencyEventHub;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps the agencies' live event streams open through proxies (each instance pings its own streams). */
@Component
@RequiredArgsConstructor
public class AgencyEventHeartbeatJob {

    private final AgencyEventHub hub;

    @Scheduled(initialDelayString = "PT25S", fixedDelayString = "PT25S")
    public void run() {
        hub.heartbeat();
    }
}
