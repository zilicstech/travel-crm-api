package com.voyra.crm.controller;

import com.voyra.crm.dto.CalendarEventResponse;
import com.voyra.crm.service.CalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Agency-wide (or agent-scoped) calendar - trip departures/returns, follow-up due dates,
 * invoice due dates, booking deadlines and visa appointments, unioned. Read-only, in-app only.
 */
@RestController
@RequestMapping("/api/calendar")
@RequiredArgsConstructor
@Tag(name = "Calendar", description = "Agency-wide (or agent-scoped) calendar feed")
@PreAuthorize("hasAnyRole('AGENCY_OWNER', 'AGENT')")
public class CalendarController {

    private final CalendarService calendarService;

    @GetMapping
    @Operation(summary = "List calendar events in a date range",
            description = "Agents see only their own; owners see the whole agency.")
    public ResponseEntity<List<CalendarEventResponse>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(calendarService.list(from, to));
    }
}
