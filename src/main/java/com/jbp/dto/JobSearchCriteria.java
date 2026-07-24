package com.jbp.dto;

import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Bundles the job-search parameters so the service signature stays readable.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobSearchCriteria {

    private String q;
    private String location;
    private Boolean remote;
    private JobType type;
    private SeniorityLevel seniority;
    private Integer salaryMin;
    private int page;
    private int size;
}
