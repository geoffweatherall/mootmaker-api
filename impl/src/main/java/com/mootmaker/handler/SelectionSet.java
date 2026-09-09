package com.mootmaker.handler;

import module java.base;

/**
 * Reads AppSync's {@code info.selectionSetList} so a resolver only fetches what the query actually
 * asked for. {@code meetings { id subject }} needs no room or person lookup at all; adding
 * {@code room { name }} costs exactly one.
 *
 * <p><b>This only works because the request template asks for it.</b> {@code $util.toJson($ctx)}
 * does <i>not</i> serialise {@code selectionSetList} - AWS documents that it and
 * {@code selectionSetGraphQL} "are not serialized by default", and it was verified absent from a
 * live response before this was written. See {@code appsync.tf}'s request template.
 *
 * <p><b>The test must fail toward fetching.</b> Aliased fields appear under the alias <i>only</i>:
 * querying {@code aliasedName: name} yields {@code room/aliasedName} and no entry for
 * {@code room/name}. A resolver that enumerated the field names requiring a lookup would conclude
 * the client did not want the name, return a stub, and hand GraphQL a null for a non-null field -
 * which nulls the Person, then the list, then potentially the meeting. Silent data loss, not a
 * degraded response. So this asks the opposite question: is every selected sub-field one we can
 * answer for free? Anything else - an alias, a fragment, a field added later - means fetch.
 */
final class SelectionSet {

    /**
     * The only sub-fields resolvable without a lookup. {@code id} is already on the stored meeting;
     * {@code __typename} is a constant, and Apollo adds it to every selection set, so treating it
     * as requiring a fetch would defeat the optimisation for every real client.
     */
    private static final Set<String> FREE_FIELDS = Set.of("id", "__typename");

    private final List<String> paths;

    private SelectionSet(final List<String> paths) {
        this.paths = paths;
    }

    /**
     * Absent or empty means fetch everything: an unrecognised payload shape must degrade to the
     * old over-fetching behaviour rather than to silent data loss.
     */
    @SuppressWarnings("unchecked")
    static SelectionSet from(final Map<String, Object> event) {
        final Object infoRaw = event == null ? null : event.get("info");
        if (!(infoRaw instanceof Map<?, ?> info)) {
            return new SelectionSet(List.of());
        }
        final Object listRaw = ((Map<String, Object>) info).get("selectionSetList");
        if (!(listRaw instanceof List<?> list) || list.isEmpty()) {
            return new SelectionSet(List.of());
        }
        return new SelectionSet(list.stream().filter(String.class::isInstance).map(String.class::cast).toList());
    }

    /**
     * True when resolving {@code prefix} needs a lookup - i.e. the query selected something under
     * it that is not free. False when the prefix was not selected at all, or only its id was.
     */
    boolean needsLookup(final String prefix) {
        if (paths.isEmpty()) {
            return true;
        }
        final String nested = prefix + "/";
        for (final String path : paths) {
            if (path.startsWith(nested) && !FREE_FIELDS.contains(path.substring(nested.length()))) {
                return true;
            }
        }
        return false;
    }
}
