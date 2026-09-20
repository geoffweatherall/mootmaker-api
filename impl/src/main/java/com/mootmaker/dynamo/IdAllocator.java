package com.mootmaker.dynamo;

import module java.base;

/**
 * Generates the short opaque ids this system uses in place of UUIDs. See
 * ../../../mootmaker/designs/archive/dynamodb-storage-compaction.md for why: a 36-byte UUID costs
 * 4-5x what an 8-character token does in every DynamoDB item that stores one, and a meeting stores
 * up to 23 of them (id, roomId, organiserId, and up to {@code MAX_ATTENDEES_PER_MEETING} attendee
 * ids).
 *
 * <p><b>Purely a token generator - no DynamoDB access, no state.</b> Collision safety is each
 * caller's own responsibility, because it depends on what the caller can already check for free:
 * {@link DayRepository} already writes a {@code PTR#<id>} pointer for a new meeting inside the same
 * transaction as the day, so giving that {@code Put} a {@code attribute_not_exists(pk)} condition
 * and drawing a fresh id on every retry of an already-existing retry loop costs nothing new. {@link
 * RoomRepository}/{@link PersonRepository} do the same with a small bounded retry of their own
 * around a conditional {@code PutItem}. Neither needs (or gets) a shared uniqueness registry - a
 * random 8-character base62 token is drawn from 62^8 (~2.18x10^14) possible values, which is many
 * orders of magnitude more than this periodically-wiped demo system will ever hold at once, so the
 * retry loop exists as a correctness guarantee rather than because a collision is expected.
 *
 * <p><b>Not every id in this system comes from here.</b> {@link
 * com.mootmaker.handler.PostConfirmationCreatePersonHandler} and the "claim points at a missing
 * Person" branch of {@code CreateMissingPersonsRepair} still call {@link #newId()} directly rather
 * than going through a repository's retrying {@code createWithNewId}-style method - both write the
 * new id to a Cognito {@code custom:personId} claim <i>before</i> the Person itself, specifically
 * so a failure between the two steps is recoverable (see those classes' own comments). Retrying
 * with a <i>different</i> id on a Person-write collision would silently break that invariant by
 * stranding the claim, so those two call sites draw one id and either write it or fail loudly -
 * never retry with a new one.
 */
public final class IdAllocator {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

  private static final int LENGTH = 8;

  private static final SecureRandom RANDOM = new SecureRandom();

  private IdAllocator() {}

  public static String newId() {
    final StringBuilder id = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return id.toString();
  }
}
