package com.hospitalscheduler.optimization;

import com.hospitalscheduler.model.Schedule;
import com.hospitalscheduler.model.AppointmentRequest;

import java.util.Set;
import java.util.stream.Collectors;

public class ScheduleCompletenessValidator {

    public static boolean isComplete(
            OptimizationProblem problem,
            Schedule schedule) {

        Set<Long> expectedIds = problem.getRequests()
                .stream()
                .map(AppointmentRequest::getId)
                .collect(Collectors.toSet());

        Set<Long> scheduledIds = schedule.getAppointments()
                .stream()
                .map(a -> a.getRequest().getId())
                .collect(Collectors.toSet());

        return expectedIds.equals(scheduledIds)
                && problem.getRequests().size()
                == schedule.getAppointments().size();
    }
}
