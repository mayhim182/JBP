package com.jbp.controller;

import com.jbp.dto.CandidateProfileRequest;
import com.jbp.dto.CandidateProfileResponse;
import com.jbp.dto.ResumeUploadResponse;
import com.jbp.service.CandidateProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/candidate")
@RequiredArgsConstructor
public class CandidateProfileController {

    private final CandidateProfileService candidateProfileService;

    @GetMapping("/profile")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<CandidateProfileResponse> getMyProfile() {
        log.debug("Fetching profile of current candidate");
        return ResponseEntity.ok(candidateProfileService.getMyProfile());
    }

    @PutMapping("/profile")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<CandidateProfileResponse> updateMyProfile(
            @Valid @RequestBody CandidateProfileRequest request) {
        log.info("Updating profile of current candidate");
        return ResponseEntity.ok(candidateProfileService.updateMyProfile(request));
    }

    @PostMapping("/resume")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ResumeUploadResponse> uploadMyResume(@RequestParam("file") MultipartFile file) {
        log.info("Uploading resume for current candidate");
        return ResponseEntity.ok(candidateProfileService.uploadMyResume(file));
    }

    @DeleteMapping("/resume")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<Void> deleteMyResume() {
        log.info("Deleting resume for current candidate");
        candidateProfileService.deleteMyResume();
        return ResponseEntity.noContent().build();
    }
}
