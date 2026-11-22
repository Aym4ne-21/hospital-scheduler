package com.hospitalscheduler.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {
    /** Saves a new candidate version; the code must be unique. */
    ScheduleSnapshot.Stored saveCandidate(ScheduleSnapshot candidate);

    Optional<ScheduleSnapshot.Stored> load(String scheduleCode);

    List<ScheduleSnapshot.Stored> loadWeek(String hospitalCode, LocalDate startsOn);
}
