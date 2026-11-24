package com.voyra.crm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** The file of one File field on one visa application; a new upload replaces it. */
@Entity
@Table(name = "visa_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VisaFile {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "visa_id", nullable = false, length = 36)
    private String visaId;

    @Column(name = "field_id", nullable = false, length = 36)
    private String fieldId;

    @Column(name = "file_key", nullable = false, length = 500)
    private String fileKey;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "uploaded_by", length = 36)
    private String uploadedBy;

    @Column(name = "uploaded_by_name", length = 150)
    private String uploadedByName;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;
}
