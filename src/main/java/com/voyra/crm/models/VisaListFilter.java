package com.voyra.crm.models;

import com.voyra.crm.enums.VisaStatus;

import java.time.LocalDate;
import java.util.Map;

/**
 * What the visa sheet asks for. {@code fieldFilters} are the raw {@code f.<field id>} query parameters (and
 * {@code f.<id>.from} / {@code f.<id>.to} for a Date field), checked against the active fields before use.
 */
public record VisaListFilter(String q, String country, VisaStatus status, String agentId,
                             LocalDate openedFrom, LocalDate openedTo, Map<String, String> fieldFilters) {

    public VisaListFilter {
        fieldFilters = fieldFilters == null ? Map.of() : Map.copyOf(fieldFilters);
    }
}
