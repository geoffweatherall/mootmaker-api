package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import org.junit.jupiter.api.Test;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResolverDispatchHandlerTest {

    private static final class RecordingHandler implements RequestHandler<Map<String, Object>, Object> {
        Map<String, Object> receivedEvent;
        final Object result = new Object();

        @Override
        public Object handleRequest(final Map<String, Object> event, final Context context) {
            this.receivedEvent = event;
            return result;
        }
    }

    @Test
    void dispatchesToTheHandlerMatchingParentTypeNameAndFieldName() {
        final RecordingHandler rooms = new RecordingHandler();
        final RecordingHandler createMeeting = new RecordingHandler();
        final ResolverDispatchHandler dispatcher = new ResolverDispatchHandler(
                Map.of("Query.rooms", rooms, "Mutation.createMeeting", createMeeting));

        final Map<String, Object> event = Map.of("info", Map.of("parentTypeName", "Query", "fieldName", "rooms"));

        final Object result = dispatcher.handleRequest(event, null);

        assertSame(rooms.result, result);
        assertEquals(event, rooms.receivedEvent);
        assertEquals(null, createMeeting.receivedEvent);
    }

    @Test
    void rejectsAnUnregisteredRoutingKey() {
        final ResolverDispatchHandler dispatcher = new ResolverDispatchHandler(Map.of("Query.rooms", new RecordingHandler()));

        final Map<String, Object> event = Map.of("info", Map.of("parentTypeName", "Query", "fieldName", "people"));

        assertThrows(IllegalStateException.class, () -> dispatcher.handleRequest(event, null));
    }

    @Test
    void rejectsAnEventWithNoInfo() {
        final ResolverDispatchHandler dispatcher = new ResolverDispatchHandler(Map.of("Query.rooms", new RecordingHandler()));

        assertThrows(IllegalStateException.class, () -> dispatcher.handleRequest(Map.of(), null));
    }
}
