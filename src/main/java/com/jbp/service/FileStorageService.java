package com.jbp.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over file storage. The current implementation writes to local disk;
 * swapping to S3 later means adding a new implementation, with no changes elsewhere.
 */
public interface FileStorageService {

    /**
     * Stores the file under the given sub-directory and returns an opaque storage key.
     */
    String store(MultipartFile file, String subDirectory);

    /**
     * Deletes the file identified by the storage key. No-op if the key is null/blank.
     */
    void delete(String key);
}
