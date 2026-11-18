package com.hospitalscheduler.model;

import java.util.Set;

public class Room {

    private final long id;
    private final String name;
    private final Set<String> equipment;

    public Room(long id, String name, Set<String> equipment) {
        this.id = id;
        this.name = name;
        this.equipment = Set.copyOf(equipment);
    }

    public long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<String> getEquipment() {
        return equipment;
    }
}