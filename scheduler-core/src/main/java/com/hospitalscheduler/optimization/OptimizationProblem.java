package com.hospitalscheduler.optimization;

import com.hospitalscheduler.model.Doctor;
import com.hospitalscheduler.model.Room;
import com.hospitalscheduler.model.AppointmentRequest;

import java.util.List;

public class OptimizationProblem {

    private final List<Doctor> doctors;
    private final List<Room> rooms;
    private final List<AppointmentRequest> requests;

    public OptimizationProblem(
            List<Doctor> doctors,
            List<Room> rooms,
            List<AppointmentRequest> requests) {

        this.doctors = List.copyOf(doctors);
        this.rooms = List.copyOf(rooms);
        this.requests = List.copyOf(requests);
    }

    public List<Doctor> getDoctors() {
        return doctors;
    }

    public List<Room> getRooms() {
        return rooms;
    }

    public List<AppointmentRequest> getRequests() {
        return requests;
    }
}