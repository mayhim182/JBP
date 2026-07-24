package com.jbp.repository;

import com.jbp.model.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApplicationRepository extends JpaRepository<Application, Long> {

    boolean existsByCandidateIdAndJobId(Long candidateId, Long jobId);

    List<Application> findByCandidateId(Long candidateId);

    List<Application> findByJobId(Long jobId);
}
