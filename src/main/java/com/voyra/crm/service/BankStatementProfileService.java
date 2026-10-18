package com.voyra.crm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voyra.crm.dto.BankStatementProfileRequest;
import com.voyra.crm.dto.BankStatementProfileResponse;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.enums.BankAmountConvention;
import com.voyra.crm.repository.BankStatementProfileRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BankStatementProfileService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final BankStatementProfileRepository bankStatementProfileRepository;

    @Transactional
    public BankStatementProfileResponse create(BankStatementProfileRequest request) {
        BankStatementProfile profile = BankStatementProfile.builder()
                .id(UniqueIdResolver.resolve(bankStatementProfileRepository::existsById))
                .createdDate(LocalDateTime.now())
                .build();
        apply(profile, request);
        bankStatementProfileRepository.save(profile);
        return toResponse(profile);
    }

    @Transactional
    public BankStatementProfileResponse update(String id, BankStatementProfileRequest request) {
        BankStatementProfile profile = find(id);
        apply(profile, request);
        bankStatementProfileRepository.save(profile);
        return toResponse(profile);
    }

    /** Soft delete - statements already imported with it stay imported. */
    @Transactional
    public void delete(String id) {
        bankStatementProfileRepository.delete(find(id));
    }

    @Transactional(readOnly = true)
    public List<BankStatementProfileResponse> list() {
        return bankStatementProfileRepository.findAll().stream().map(BankStatementProfileService::toResponse).toList();
    }

    /** The same checks the CSV parser makes at import time, made here so a broken profile is refused when saved. */
    private static void apply(BankStatementProfile profile, BankStatementProfileRequest request) {
        String fileFormat = request.getFileFormat() != null ? request.getFileFormat() : "CSV";
        if (!"CSV".equalsIgnoreCase(fileFormat)) {
            throw new IllegalArgumentException("Only CSV statements can be imported");
        }
        try {
            DateTimeFormatter.ofPattern(request.getDateFormat());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + request.getDateFormat() + "\" is not a valid date format - use a pattern such as dd/MM/yyyy");
        }
        Map<String, Object> columns;
        try {
            columns = JSON.readValue(request.getColumnMap(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("The column map is not valid JSON - write it as {\"date\":\"Txn Date\", \"amount\":\"Amount\", ...}");
        }
        List<String> required = request.getAmountConvention() == BankAmountConvention.DEBIT_CREDIT_COLUMNS
                ? List.of("date", "debit", "credit") : List.of("date", "amount");
        for (String field : required) {
            if (!(columns.get(field) instanceof String column) || column.isBlank()) {
                throw new IllegalArgumentException("The column map needs the statement's \"" + field + "\" column");
            }
        }
        profile.setBankName(request.getBankName());
        profile.setFileFormat(fileFormat.toUpperCase());
        profile.setDateFormat(request.getDateFormat());
        profile.setColumnMap(request.getColumnMap());
        profile.setAmountConvention(request.getAmountConvention());
    }

    private BankStatementProfile find(String id) {
        return bankStatementProfileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Statement profile not found: " + id));
    }

    private static BankStatementProfileResponse toResponse(BankStatementProfile p) {
        return BankStatementProfileResponse.builder()
                .id(p.getId()).bankName(p.getBankName()).fileFormat(p.getFileFormat())
                .dateFormat(p.getDateFormat()).columnMap(p.getColumnMap()).amountConvention(p.getAmountConvention())
                .build();
    }
}
