package com.hospitalscheduler.repository;

/** The entity cannot be deleted while other records still use it. */
public class HospitalEntityInUseException extends HospitalRepositoryException {
    public HospitalEntityInUseException(String entity, String code, Throwable cause) {
        super("Cannot delete " + entity + " '" + code + "': it is still used by other records.", cause);
    }
}
