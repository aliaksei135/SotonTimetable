package com.aliakseipilko.sotontimetable.models.soton;


import java.util.List;

public class TimetableJsonModel {
    public List<EventJsonModel> events;

    public void addEvent(EventJsonModel e) {
        events.add(e);
    }
}
