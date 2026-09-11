package com.java2nb.novel.engagement;

public class ReadingEventConflictException extends IllegalArgumentException {

    private final String eventId;

    public ReadingEventConflictException(String eventId) {
        super("Conflicting payload for reading event ID");
        this.eventId = eventId;
    }

    public String eventId() {
        return eventId;
    }
}
