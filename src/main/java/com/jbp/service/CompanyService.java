package com.jbp.service;

import com.jbp.dto.CompanyRequest;
import com.jbp.dto.CompanyResponse;
import com.jbp.model.Company;

public interface CompanyService {

    CompanyResponse createCompanyForCurrentRecruiter(CompanyRequest request);

    CompanyResponse getCurrentRecruiterCompany();

    CompanyResponse getCompanyById(Long id);

    CompanyResponse updateCompany(Long id, CompanyRequest request);

    /**
     * Returns the recruiter's company entity for use by other services (e.g. jobs),
     * so they don't reach into the company repository directly. Throws if none exists.
     */
    Company getCompanyEntityForRecruiter(Long recruiterId);

    /**
     * Seam for the jobs module (Epic 3): can this recruiter's company publish jobs?
     * Returns true only when the recruiter owns a VERIFIED company.
     */
    boolean isRecruiterVerified(Long recruiterId);
}
