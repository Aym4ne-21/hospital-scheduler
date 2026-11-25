package com.hospitalscheduler.repository;

/** A persistence operation failed; the underlying cause is available for diagnostics. */
public class HospitalRepositoryException extends RuntimeException {
    public HospitalRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
