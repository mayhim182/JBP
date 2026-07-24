package com.jbp.service;

import com.jbp.dto.JobResponse;
import com.jbp.dto.JobSearchCriteria;
import com.jbp.dto.PageResponse;

/**
 * Job search over PUBLISHED jobs. Behind this interface today sits MySQL FULLTEXT;
 * it can be swapped for another engine (e.g. Elasticsearch) without touching callers.
 */
public interface JobSearchService {

    PageResponse<JobResponse> search(JobSearchCriteria criteria);
}
