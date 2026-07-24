package com.jbp.service;

import com.jbp.dto.CandidateProfileRequest;
import com.jbp.dto.CandidateProfileResponse;
import com.jbp.dto.ResumeUploadResponse;
import com.jbp.model.CandidateProfile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

public interface CandidateProfileService {

    CandidateProfileResponse getMyProfile();

    CandidateProfileResponse updateMyProfile(CandidateProfileRequest request);

    ResumeUploadResponse uploadMyResume(MultipartFile file);

    void deleteMyResume();

    /** Seam for the matching module (Epic 7): the structured profile of a candidate. */
    CandidateProfile getProfileEntityForCandidate(Long candidateId);

    /** Read-only profile lookup (no create-if-missing); used by matching/ranking. */
    Optional<CandidateProfile> findProfileForCandidate(Long candidateId);
}
