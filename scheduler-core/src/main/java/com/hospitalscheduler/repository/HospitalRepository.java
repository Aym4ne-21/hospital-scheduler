package com.hospitalscheduler.repository;

import java.time.LocalDate;
import com.hospitalscheduler.optimization.OptimizationProblem;
import java.util.Optional;

public interface HospitalRepository {
    /** Loads the shared model consumed by Optimizer and FitnessEvaluator. */
    Optional<OptimizationProblem> loadProblem(String hospitalCode, LocalDate startsOn);

    Optional<HospitalWeek> loadWeek(String hospitalCode, LocalDate startsOn);

    /** Upserts the supplied entities atomically; dated child lists replace only this week's windows. */
    void saveWeek(HospitalWeek week);

    /** Deletions return false when no matching record exists; referenced records are protected. */
    boolean deleteHospital(String hospitalCode);

    boolean deleteDepartment(String hospitalCode, String departmentCode);

    boolean deleteSpecialty(String hospitalCode, String specialtyCode);

    boolean deleteDoctor(String hospitalCode, String doctorCode);

    boolean deleteRoom(String hospitalCode, String roomCode);

    boolean deleteRequest(String hospitalCode, String requestCode);

    /** Patients and these catalogs are shared globally, so they have no hospital scope. */
    boolean deletePatient(String patientCode);

    boolean deleteRoomType(String roomTypeCode);

    boolean deleteEquipment(String equipmentCode);
}
