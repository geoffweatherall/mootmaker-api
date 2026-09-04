package com.mootmaker.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.mootmaker.dynamo.DynamoDbClientProvider;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import module java.base;

/** AppSync direct-Lambda resolver for {@code Query.people}. */
public class ListPeopleHandler implements RequestHandler<Map<String, Object>, Object> {

    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public ListPeopleHandler() {
        this(DynamoDbClientProvider.client(), System.getenv().getOrDefault("PEOPLE_TABLE_NAME", "People"));
    }

    ListPeopleHandler(final DynamoDbClient dynamoDbClient, final String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    @Override
    public Object handleRequest(final Map<String, Object> event, final Context context) {
        Identity.requireAuthenticated(event);

        // Consistent read, deliberately. DynamoDB's Scan defaults to EVENTUALLY consistent, so a
        // Scan issued immediately after the PutItem that created an item can legitimately not see
        // it yet. The webapp does exactly that: SettingsPage's dialog calls refetch() the moment
        // the create mutation returns, and nothing refetches again afterwards - so a stale read
        // renders a list missing the row the user just added, permanently, until something else
        // triggers a fetch.
        //
        // That is the shape behind mootmaker-webapp#1 and #12: ~1-2 acceptance tests per run
        // failing on `expect(page.getByText(name)).toBeVisible()` right after a create, a
        // different test almost every time, on freshly deployed environments. Not a test problem.
        //
        // Cost is 2x the read units of an eventually consistent read, on PAY_PER_REQUEST tables
        // holding a few hundred rows - immaterial here.
        //
        // NOTE for mootmaker-api#2 (replacing these Scans with GSI queries): a GSI can ONLY be
        // read eventually consistently - DynamoDB rejects ConsistentRead on an index. So that
        // change would reintroduce this bug unless it queries the table itself rather than an
        // index. This comment is the warning.
        final ScanResponse response = dynamoDbClient.scan(ScanRequest.builder().tableName(tableName).consistentRead(true).build());
        return response.items().stream()
                .map(Person::fromItem)
                .map(Person::toResponseMap)
                .toList();
    }
}
