package com.jbp.serviceimpl;

import com.jbp.dto.CompanyRequest;
import com.jbp.dto.CompanyResponse;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.CompanyMapper;
import com.jbp.model.Company;
import com.jbp.model.User;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.CompanyRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CompanyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompanyServiceImpl implements CompanyService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final CompanyMapper companyMapper;

    @Override
    @Transactional
    public CompanyResponse createCompanyForCurrentRecruiter(CompanyRequest request) {
        Long recruiterId = currentUserProvider.getCurrentUserId();
        if (companyRepository.existsByOwnerId(recruiterId)) {
            log.warn("Company creation rejected — recruiter {} already owns a company", recruiterId);
            throw new ConflictException("This recruiter already has a company");
        }

        User owner = findUserOrThrow(recruiterId);
        Company company = Company.builder()
                .name(request.getName())
                .description(request.getDescription())
                .website(request.getWebsite())
                .logo(request.getLogo())
                .location(request.getLocation())
                .status(VerificationStatus.PENDING) // awaits admin verification (Epic 9)
                .owner(owner)
                .build();

        Company saved = companyRepository.save(company);
        log.info("Company created with id={} for recruiter {} (PENDING)", saved.getId(), recruiterId);
        return companyMapper.toResponse(saved);
    }

    @Override
    public CompanyResponse getCurrentRecruiterCompany() {
        Long recruiterId = currentUserProvider.getCurrentUserId();
        return companyMapper.toResponse(getCompanyEntityForRecruiter(recruiterId));
    }

    @Override
    public Company getCompanyEntityForRecruiter(Long recruiterId) {
        return companyRepository.findByOwnerId(recruiterId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No company found for recruiter id: " + recruiterId));
    }

    @Override
    public CompanyResponse getCompanyById(Long id) {
        return companyMapper.toResponse(findCompanyOrThrow(id));
    }

    @Override
    @Transactional
    public CompanyResponse updateCompany(Long id, CompanyRequest request) {
        Company company = findCompanyOrThrow(id);
        ensureCurrentUserOwns(company);

        company.setName(request.getName());
        company.setDescription(request.getDescription());
        company.setWebsite(request.getWebsite());
        company.setLogo(request.getLogo());
        company.setLocation(request.getLocation());

        Company updated = companyRepository.save(company);
        log.info("Company updated with id={}", updated.getId());
        return companyMapper.toResponse(updated);
    }

    @Override
    public boolean isRecruiterVerified(Long recruiterId) {
        return companyRepository.findByOwnerId(recruiterId)
                .map(company -> company.getStatus() == VerificationStatus.VERIFIED)
                .orElse(false);
    }

    private void ensureCurrentUserOwns(Company company) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!company.getOwner().getId().equals(currentUserId)) {
            log.warn("User {} attempted to modify company {} owned by {}",
                    currentUserId, company.getId(), company.getOwner().getId());
            throw new AccessDeniedException("You can only manage your own company");
        }
    }

    private Company findCompanyOrThrow(Long id) {
        return companyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Company not found with id: " + id));
    }

    private User findUserOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));
    }
}
