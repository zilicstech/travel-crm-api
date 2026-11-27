package com.voyra.crm.controller;

import com.voyra.crm.context.TenantContext;
import com.voyra.crm.service.AgencyEventHub;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Live events", description = "A Server-Sent Events stream telling the agency's screens to re-read their counts")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT', 'ACCOUNTANT')")
public class AgencyEventController {

    private final AgencyEventHub hub;

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live change signals", description = "An event named 'changed' (no data) after any write in the agency; comments keep it open")
    public SseEmitter stream(HttpServletResponse response) {
        // no proxy buffering, no caching: each event must reach the browser at once
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
        return hub.subscribe(TenantContext.getTenantId());
    }
}
