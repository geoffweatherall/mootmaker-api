package com.mootmaker.handler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Driven by the shape AppSync actually sends - a flat list of "/"-separated paths relative to the
 * resolved field - rather than by end-to-end queries, because the cases that matter (aliases,
 * fragments, a missing key) are the ones an end-to-end test is least likely to cover by accident.
 */
class SelectionSetTest {

  private static SelectionSet selecting(final String... paths) {
    return SelectionSet.from(Map.of("info", Map.of("selectionSetList", List.of(paths))));
  }

  @Nested
  @DisplayName("costs no lookup")
  class NoLookup {

    @Test
    void whenOnlyScalarsOnTheMeetingAreSelected() {
      final SelectionSet selection = selecting("id", "subject", "startTime", "endTime");
      assertFalse(selection.needsLookup("room"));
      assertFalse(selection.needsLookup("organiser"));
      assertFalse(selection.needsLookup("attendees"));
    }

    @Test
    void whenNestedSelectionsAskOnlyForIds() {
      final SelectionSet selection =
          selecting(
              "id", "room", "room/id", "organiser", "organiser/id", "attendees", "attendees/id");
      assertFalse(selection.needsLookup("room"));
      assertFalse(selection.needsLookup("organiser"));
      assertFalse(selection.needsLookup("attendees"));
    }

    @Test
    @DisplayName(
        "__typename is free - Apollo adds it to every selection set, so charging for it would"
            + " defeat the optimisation entirely")
    void whenNestedSelectionsAskForIdAndTypename() {
      assertFalse(selecting("room", "room/id", "room/__typename").needsLookup("room"));
    }

    @Test
    @DisplayName("a sibling needing a lookup does not drag in the others")
    void whenOnlyAnotherPathNeedsResolving() {
      final SelectionSet selection = selecting("room", "room/name", "attendees", "attendees/id");
      assertTrue(selection.needsLookup("room"));
      assertFalse(selection.needsLookup("attendees"));
    }
  }

  @Nested
  @DisplayName("needs a lookup")
  class NeedsLookup {

    @Test
    void whenANestedFieldBeyondTheIdIsSelected() {
      assertTrue(selecting("room", "room/id", "room/name").needsLookup("room"));
      assertTrue(selecting("organiser", "organiser/name").needsLookup("organiser"));
      assertTrue(selecting("attendees", "attendees/name").needsLookup("attendees"));
    }

    @Test
    @DisplayName(
        "an ALIASED field appears under the alias only - the case that would otherwise return a"
            + " stub and null a non-null field")
    void whenTheFieldIsAliased() {
      assertTrue(selecting("room", "room/id", "room/aliasedName").needsLookup("room"));
      assertTrue(selecting("attendees", "attendees/who").needsLookup("attendees"));
    }

    @Test
    @DisplayName(
        "fragments are flattened by AppSync into ordinary paths, so they need no special handling -"
            + " but assert it rather than assume it")
    void whenTheSelectionCameFromAFragment() {
      assertTrue(selecting("room", "room/id", "room/name", "room/capacity").needsLookup("room"));
    }

    @Test
    @DisplayName("a field nobody has thought of yet must fetch, not stub")
    void whenAnUnknownNestedFieldIsSelected() {
      assertTrue(selecting("organiser", "organiser/somethingAddedLater").needsLookup("organiser"));
    }

    @Test
    @DisplayName("deeper nesting is not free either")
    void whenTheSelectionGoesDeeper() {
      assertTrue(selecting("organiser", "organiser/manager/id").needsLookup("organiser"));
    }
  }

  @Nested
  @DisplayName("degrades to fetching everything")
  class FailsTowardFetching {

    @Test
    @DisplayName(
        "an absent selectionSetList means the request template has not been updated - over-fetch"
            + " rather than lose data")
    void whenInfoHasNoSelectionSetList() {
      final SelectionSet selection =
          SelectionSet.from(Map.of("info", Map.of("fieldName", "meetings")));
      assertTrue(selection.needsLookup("room"));
      assertTrue(selection.needsLookup("organiser"));
      assertTrue(selection.needsLookup("attendees"));
    }

    @Test
    void whenThereIsNoInfoAtAll() {
      assertTrue(SelectionSet.from(Map.of()).needsLookup("room"));
    }

    @Test
    void whenTheEventIsNull() {
      assertTrue(SelectionSet.from(null).needsLookup("room"));
    }

    @Test
    void whenTheSelectionSetListIsEmpty() {
      assertTrue(selecting().needsLookup("room"));
    }

    @Test
    @DisplayName("a selectionSetList of an unexpected type is not trusted")
    void whenTheSelectionSetListIsNotAList() {
      final SelectionSet selection =
          SelectionSet.from(Map.of("info", Map.of("selectionSetList", "room/id")));
      assertTrue(selection.needsLookup("room"));
    }
  }
}
