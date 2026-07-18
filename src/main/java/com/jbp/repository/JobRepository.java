package com.jbp.repository;

import com.jbp.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JobRepository extends JpaRepository<Job, Long> {

    // All jobs owned by a recruiter, resolved via job -> company -> owner.
    List<Job> findByCompany_Owner_Id(Long ownerId);
}
