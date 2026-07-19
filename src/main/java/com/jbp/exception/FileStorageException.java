package com.jbp.exception;

/**
 * Thrown when storing or deleting a file fails (I/O error, unreadable upload, etc.).
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
