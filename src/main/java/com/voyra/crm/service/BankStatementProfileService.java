package com.voyra.crm.service;

import com.voyra.crm.dto.BankStatementProfileRequest;
import com.voyra.crm.dto.BankStatementProfileResponse;
import com.voyra.crm.entity.BankStatementProfile;
import com.voyra.crm.repository.BankStatementProfileRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BankStatementProfileService {

    private final BankStatementProfileRepository bankStatementProfileRepository;

    @Transactional
    public BankStatementProfileResponse create(BankStatementProfileRequest request) {
        BankStatementProfile profile = BankStatementProfile.builder()
                .id(UniqueIdResolver.resolve(bankStatementProfileRepository::existsById))
                .bankName(request.getBankName())
                .fileFormat(request.getFileFormat() != null ? request.getFileFormat() : "CSV")
                .dateFormat(request.getDateFormat())
                .columnMap(request.getColumnMap())
                .amountConvention(request.getAmountConvention())
                .createdDate(LocalDateTime.now())
                .build();
        bankStatementProfileRepository.save(profile);
        return toResponse(profile);
    }

    @Transactional(readOnly = true)
    public List<BankStatementProfileResponse> list() {
        return bankStatementProfileRepository.findAll().stream().map(BankStatementProfileService::toResponse).toList();
    }

    private static BankStatementProfileResponse toResponse(BankStatementProfile p) {
        return BankStatementProfileResponse.builder()
                .id(p.getId()).bankName(p.getBankName()).fileFormat(p.getFileFormat())
                .dateFormat(p.getDateFormat()).columnMap(p.getColumnMap()).amountConvention(p.getAmountConvention())
                .build();
    }
}
