package com.hospitalscheduler.repository;

import java.time.LocalDate;
import java.util.Optional;

public interface HospitalRepository {
    Optional<HospitalWeek> loadWeek(String hospitalCode, LocalDate startsOn);

    /** Upserts the supplied entities atomically; dated child lists replace only this week's windows. */
    void saveWeek(HospitalWeek week);
}
