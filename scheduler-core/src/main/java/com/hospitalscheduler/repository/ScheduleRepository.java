package com.hospitalscheduler.repository;

import java.time.LocalDate;
import java.util.Set;
import com.hospitalscheduler.model.Schedule;
import com.hospitalscheduler.optimization.OptimizationProblem;
import com.hospitalscheduler.optimization.OptimizationResult;
import java.util.List;
import java.util.Optional;

public interface ScheduleRepository {
    ScheduleSnapshot.StoredResult saveResult(ScheduleSnapshot.RunMetadata metadata,
                                              OptimizationResult result, Set<Long> lockedRequestIds);

    Optional<ScheduleSnapshot.StoredResult> loadResult(String scheduleCode, OptimizationProblem problem);

    Optional<Schedule> loadSchedule(String scheduleCode, OptimizationProblem problem);

    /** Saves a new candidate version; the code must be unique. */
    ScheduleSnapshot.Stored saveCandidate(ScheduleSnapshot candidate);

    Optional<ScheduleSnapshot.Stored> load(String scheduleCode);

    List<ScheduleSnapshot.Stored> loadWeek(String hospitalCode, LocalDate startsOn);
}
