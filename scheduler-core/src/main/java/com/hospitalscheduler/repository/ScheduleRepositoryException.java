package com.hospitalscheduler.repository;

/** A schedule persistence operation failed; the cause retains SQL diagnostics. */
public class ScheduleRepositoryException extends RuntimeException {
    public ScheduleRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
