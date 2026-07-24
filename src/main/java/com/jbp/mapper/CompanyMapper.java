package com.jbp.mapper;

import com.jbp.dto.CompanyResponse;
import com.jbp.model.Company;
import com.jbp.model.VerificationStatus;
import org.springframework.stereotype.Component;

/**
 * Single place that turns a {@link Company} into a {@link CompanyResponse}, shared by
 * the company service and the admin verification flow (DRY).
 */
@Component
public class CompanyMapper {

    public CompanyResponse toResponse(Company company) {
        return CompanyResponse.builder()
                .id(company.getId())
                .name(company.getName())
                .description(company.getDescription())
                .website(company.getWebsite())
                .logo(company.getLogo())
                .location(company.getLocation())
                .status(company.getStatus())
                .verified(company.getStatus() == VerificationStatus.VERIFIED)
                .ownerId(company.getOwner().getId())
                .build();
    }
}
