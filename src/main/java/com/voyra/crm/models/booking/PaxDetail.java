package com.voyra.crm.models.booking;

import java.time.LocalDate;

/**
 * One traveller. {@code type} is ADULT, CHILD or INFANT; passport fields are optional (domestic);
 * {@code documentId} is the ID number a special fare (student, senior citizen) asks for, else null.
 */
public record PaxDetail(String type, String title, String firstName, String lastName, LocalDate dateOfBirth,
                        String passportNumber, LocalDate passportExpiry, String nationality, String documentId) {
}
