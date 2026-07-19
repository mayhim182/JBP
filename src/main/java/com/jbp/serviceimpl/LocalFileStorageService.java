package com.jbp.serviceimpl;

import com.jbp.exception.FileStorageException;
import com.jbp.service.FileStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Stores files on the local filesystem under a configurable base directory.
 * Swapping to S3 later means adding a new {@link FileStorageService} implementation.
 */
@Service
public class LocalFileStorageService implements FileStorageService {

    private final Path baseDirectory;

    public LocalFileStorageService(@Value("${app.storage.location:uploads}") String baseLocation) {
        this.baseDirectory = Paths.get(baseLocation).toAbsolutePath().normalize();
    }

    @Override
    public String store(MultipartFile file, String subDirectory) {
        try {
            Path targetDirectory = baseDirectory.resolve(subDirectory).normalize();
            Files.createDirectories(targetDirectory);

            String storedName = UUID.randomUUID() + extensionOf(file.getOriginalFilename());
            Path target = targetDirectory.resolve(storedName);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return subDirectory + "/" + storedName;
        } catch (IOException e) {
            throw new FileStorageException("Failed to store file", e);
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            Path target = baseDirectory.resolve(key).normalize();
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new FileStorageException("Failed to delete file", e);
        }
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        return dot >= 0 ? originalFilename.substring(dot) : "";
    }
}
