package com.jbp.service;

import com.jbp.dto.CandidateProfileRequest;
import com.jbp.dto.CandidateProfileResponse;
import com.jbp.dto.ResumeUploadResponse;
import com.jbp.model.CandidateProfile;
import org.springframework.web.multipart.MultipartFile;

public interface CandidateProfileService {

    CandidateProfileResponse getMyProfile();

    CandidateProfileResponse updateMyProfile(CandidateProfileRequest request);

    ResumeUploadResponse uploadMyResume(MultipartFile file);

    void deleteMyResume();

    /** Seam for the matching module (Epic 7): the structured profile of a candidate. */
    CandidateProfile getProfileEntityForCandidate(Long candidateId);
}
