package com.jbp.repository;

import com.jbp.model.Company;
import com.jbp.model.VerificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByOwnerId(Long ownerId);

    boolean existsByOwnerId(Long ownerId);

    List<Company> findByStatus(VerificationStatus status);

    long countByStatus(VerificationStatus status);
}
