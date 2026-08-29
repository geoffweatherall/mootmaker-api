package com.mootmaker.model;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import module java.base;

/**
 * One row per (meeting, participant) pair - the organiser or an attendee - in the
 * meeting-participants join table. attendeeIds is a list on the meeting item, and DynamoDB keys
 * must be scalars, so "which meetings is this person organiser of or an attendee on" can't be
 * answered with a GSI on the meetings table itself; this table exists purely to answer it in a
 * single Query. The meetings table remains the source of truth - CreateMeetingHandler writes a
 * meeting's participant rows in the same {@code TransactWriteItems} call as the meeting itself,
 * and mootmaker-admin-tools/database-repair's RebuildMeetingParticipantsRepair can regenerate this
 * table from the meetings table if the two ever drift.
 */
public record MeetingParticipant(String personId, String meetingId, String startTime, String endTime) {

    /**
     * startTime + "#" + meetingId. startTime must already be in the fixed-width canonical form
     * CreateMeetingHandler stores (see its DATE_TIME_FORMAT) for this to sort correctly as a
     * plain string. Appending meetingId keeps the key unique even if two of a person's meetings
     * start at the same instant.
     */
    public String sortKey() {
        return startTime + "#" + meetingId;
    }

    public Map<String, AttributeValue> toItem() {
        return Map.of(
                "personId", AttributeValue.builder().s(personId).build(),
                "sortKey", AttributeValue.builder().s(sortKey()).build(),
                "meetingId", AttributeValue.builder().s(meetingId).build(),
                "startTime", AttributeValue.builder().s(startTime).build(),
                "endTime", AttributeValue.builder().s(endTime).build());
    }

    public static MeetingParticipant fromItem(final Map<String, AttributeValue> item) {
        return new MeetingParticipant(
                item.get("personId").s(),
                item.get("meetingId").s(),
                item.get("startTime").s(),
                item.get("endTime").s());
    }

    /** The organiser plus every attendee of the given meeting, i.e. every row it should have here. */
    public static List<MeetingParticipant> allFor(final MeetingRecord meeting) {
        final List<MeetingParticipant> participants = new ArrayList<>();
        participants.add(new MeetingParticipant(meeting.organiserId(), meeting.id(), meeting.startTime(), meeting.endTime()));
        for (final String attendeeId : meeting.attendeeIds()) {
            participants.add(new MeetingParticipant(attendeeId, meeting.id(), meeting.startTime(), meeting.endTime()));
        }
        return participants;
    }
}
