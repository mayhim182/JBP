package com.jbp.serviceimpl;

import com.jbp.dto.CompanyResponse;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.CompanyMapper;
import com.jbp.model.Company;
import com.jbp.model.NotificationType;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.CompanyRepository;
import com.jbp.service.AdminCompanyService;
import com.jbp.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCompanyServiceImpl implements AdminCompanyService {

    private final CompanyRepository companyRepository;
    private final CompanyMapper companyMapper;
    private final NotificationService notificationService;

    @Override
    public List<CompanyResponse> getPendingCompanies() {
        return companyRepository.findByStatus(VerificationStatus.PENDING).stream()
                .map(companyMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CompanyResponse approveCompany(Long companyId) {
        Company company = findPendingCompanyOrThrow(companyId);
        company.setStatus(VerificationStatus.VERIFIED);
        companyRepository.save(company);
        log.info("Company {} verified by admin", companyId);
        notificationService.createNotification(company.getOwner().getId(), NotificationType.COMPANY_VERIFICATION,
                "Your company '" + company.getName() + "' has been verified.");
        return companyMapper.toResponse(company);
    }

    @Override
    @Transactional
    public CompanyResponse rejectCompany(Long companyId, String reason) {
        Company company = findPendingCompanyOrThrow(companyId);
        company.setStatus(VerificationStatus.REJECTED);
        companyRepository.save(company);
        log.info("Company {} rejected by admin", companyId);
        String suffix = (reason == null || reason.isBlank()) ? "." : ": " + reason;
        notificationService.createNotification(company.getOwner().getId(), NotificationType.COMPANY_VERIFICATION,
                "Your company '" + company.getName() + "' was not verified" + suffix);
        return companyMapper.toResponse(company);
    }

    private Company findPendingCompanyOrThrow(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + companyId));
        if (company.getStatus() != VerificationStatus.PENDING) {
            throw new ConflictException("Company is not pending verification");
        }
        return company;
    }
}
