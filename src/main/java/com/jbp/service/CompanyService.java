package com.jbp.service;

import com.jbp.dto.CompanyRequest;
import com.jbp.dto.CompanyResponse;

public interface CompanyService {

    CompanyResponse createCompanyForCurrentRecruiter(CompanyRequest request);

    CompanyResponse getCurrentRecruiterCompany();

    CompanyResponse getCompanyById(Long id);

    CompanyResponse updateCompany(Long id, CompanyRequest request);

    /**
     * Seam for the jobs module (Epic 3): can this recruiter's company publish jobs?
     * Returns true only when the recruiter owns a VERIFIED company.
     */
    boolean isRecruiterVerified(Long recruiterId);
}
