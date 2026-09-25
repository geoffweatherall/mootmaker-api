package com.mootmaker.model;

/**
 * Mirrors the GraphQL {@code RoomError} enum. Constant names must match the schema's enum value
 * names exactly, since AppSync serializes/validates enum values as these literal strings.
 */
public enum RoomError {
  NameRequired,
  CapacityTooLow,
  /** updateRoom/deleteRoom only: id did not match any existing room. */
  RoomNotFound,
  /** deleteRoom only: at least one meeting from today onward is still booked in this room. */
  RoomHasUpcomingMeetings
}
