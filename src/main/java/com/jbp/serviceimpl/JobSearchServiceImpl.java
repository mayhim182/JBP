package com.jbp.serviceimpl;

import com.jbp.dto.JobResponse;
import com.jbp.dto.JobSearchCriteria;
import com.jbp.dto.PageResponse;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Job;
import com.jbp.repository.JobRepository;
import com.jbp.service.JobSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobSearchServiceImpl implements JobSearchService {

    private static final int DEFAULT_PAGE_SIZE = 10;

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;

    @Override
    public PageResponse<JobResponse> search(JobSearchCriteria criteria) {
        // Ordering (relevance, then recent) is baked into the native query; keep the page request plain.
        Pageable pageable = PageRequest.of(
                Math.max(criteria.getPage(), 0),
                criteria.getSize() > 0 ? criteria.getSize() : DEFAULT_PAGE_SIZE);

        String keyword = trimToNull(criteria.getQ());
        String keywordLike = keyword != null ? "%" + keyword.toLowerCase() + "%" : null;
        String locationLike = trimToNull(criteria.getLocation()) != null
                ? "%" + criteria.getLocation().trim().toLowerCase() + "%" : null;
        String type = criteria.getType() != null ? criteria.getType().name() : null;
        String seniority = criteria.getSeniority() != null ? criteria.getSeniority().name() : null;

        Page<Job> page = jobRepository.searchPublished(
                keyword, keywordLike, locationLike, criteria.getRemote(), type, seniority,
                criteria.getSalaryMin(), pageable);

        List<JobResponse> content = page.getContent().stream().map(jobMapper::toResponse).toList();
        return PageResponse.<JobResponse>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
