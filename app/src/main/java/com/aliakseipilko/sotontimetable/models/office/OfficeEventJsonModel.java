package com.aliakseipilko.sotontimetable.models.office;

import com.microsoft.graph.extensions.DateTimeTimeZone;
import com.microsoft.graph.extensions.ItemBody;
import com.microsoft.graph.extensions.Location;

public class OfficeEventJsonModel {

    String iCalUId;
    DateTimeTimeZone start;
    DateTimeTimeZone end;
    Location location;
    ItemBody body;
    String subject;
    String showAs = "Busy";
    Integer reminderMinutesBeforeStart = 20;
    String seriesMasterId = "University Timetable";

    public String getiCalUId() {
        return iCalUId;
    }

    public void setiCalUId(String iCalUId) {
        this.iCalUId = iCalUId;
    }

    public DateTimeTimeZone getStart() {
        return start;
    }

    public void setStart(DateTimeTimeZone start) {
        this.start = start;
    }

    public DateTimeTimeZone getEnd() {
        return end;
    }

    public void setEnd(DateTimeTimeZone end) {
        this.end = end;
    }

    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public ItemBody getBody() {
        return body;
    }

    public void setBody(ItemBody body) {
        this.body = body;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }
}
