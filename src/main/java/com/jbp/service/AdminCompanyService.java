package com.jbp.service;

import com.jbp.dto.CompanyResponse;

import java.util.List;

/** Admin recruiter/company verification (Story 9.1). */
public interface AdminCompanyService {

    List<CompanyResponse> getPendingCompanies();

    CompanyResponse approveCompany(Long companyId);

    CompanyResponse rejectCompany(Long companyId, String reason);
}
