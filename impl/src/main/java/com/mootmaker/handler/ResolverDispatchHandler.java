package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

import module java.base;

/**
 * Single entry point for every AppSync direct-Lambda resolver (see appsync.tf), replacing one
 * Lambda function per GraphQL field with one function shared by all of them - so a user's burst of
 * calls across several fields can land on the same already-warm/restored execution environment
 * instead of each field independently paying its own SnapStart restore. Purely a switchboard: all
 * business logic still lives in the per-field handler classes below, unchanged.
 *
 * <p>AppSync's {@code $context.info} ({@code fieldName}, {@code parentTypeName}) is already
 * present in every request today via the shared pass-through request template
 * ({@code $util.toJson($ctx)}), so no resolver/template changes were needed to add routing - this
 * class is the only new piece.
 */
public class ResolverDispatchHandler implements RequestHandler<Map<String, Object>, Object> {

    private final Map<String, RequestHandler<Map<String, Object>, Object>> handlersByRoutingKey;

    public ResolverDispatchHandler() {
        // Constructed eagerly (one new XxxHandler() per entry) rather than looked up/instantiated
        // per-request, so every operation is exercised during Lambda INIT and swept into the
        // SnapStart snapshot - matching DynamoDbClientProvider's own priming strategy. This does
        // not create multiple DynamoDB/Cognito clients: those providers are static singletons, so
        // the first XxxHandler() constructor call creates/primes the shared client and the rest
        // just read it.
        this(Map.ofEntries(
                Map.entry("Query.rooms", new ListRoomsHandler()),
                Map.entry("Query.people", new ListPeopleHandler()),
                Map.entry("Query.myPerson", new MyPersonHandler()),
                Map.entry("Query.meetings", new ListMeetingsHandler()),
                Map.entry("Query.suggestRoom", new SuggestRoomHandler()),
                Map.entry("Mutation.createRoom", new CreateRoomHandler()),
                Map.entry("Mutation.updateRoom", new UpdateRoomHandler()),
                Map.entry("Mutation.createPerson", new CreatePersonHandler()),
                Map.entry("Mutation.updatePerson", new UpdatePersonHandler()),
                Map.entry("Mutation.createMeeting", new CreateMeetingHandler())));
    }

    ResolverDispatchHandler(final Map<String, RequestHandler<Map<String, Object>, Object>> handlersByRoutingKey) {
        this.handlersByRoutingKey = handlersByRoutingKey;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        final String routingKey = routingKey(event);
        final RequestHandler<Map<String, Object>, Object> handler = handlersByRoutingKey.get(routingKey);
        if (handler == null) {
            throw new IllegalStateException("Bad Request: no resolver registered for '" + routingKey + "'");
        }
        return handler.handleRequest(event, context);
    }

    @SuppressWarnings("unchecked")
    private static String routingKey(final Map<String, Object> event) {
        final Object infoRaw = event == null ? null : event.get("info");
        if (!(infoRaw instanceof Map<?, ?>)) {
            throw new IllegalStateException("Bad Request: event has no 'info' - not an AppSync resolver invocation");
        }
        final Map<String, Object> info = (Map<String, Object>) infoRaw;
        return info.get("parentTypeName") + "." + info.get("fieldName");
    }
}
