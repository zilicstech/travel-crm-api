package com.voyra.crm.models.booking;

import java.time.LocalDate;
import java.util.List;

/**
 * One traveller. {@code type} is ADULT, CHILD or INFANT; passport fields are optional (domestic);
 * {@code documentId} is the ID number a special fare (student, senior citizen) asks for, else null;
 * {@code pan} and {@code seats} are optional flight traveller compliance/SSR fields.
 */
public record PaxDetail(String type, String title, String firstName, String lastName, LocalDate dateOfBirth,
                        String passportNumber, LocalDate passportExpiry, String nationality, String documentId,
                        String pan, List<SeatSelection> seats) {

    public PaxDetail(String type, String title, String firstName, String lastName, LocalDate dateOfBirth,
                     String passportNumber, LocalDate passportExpiry, String nationality, String documentId) {
        this(type, title, firstName, lastName, dateOfBirth, passportNumber, passportExpiry, nationality, documentId, null, List.of());
    }
}
