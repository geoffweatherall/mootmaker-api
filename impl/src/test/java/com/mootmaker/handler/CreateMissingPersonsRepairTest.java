package com.mootmaker.handler;

import com.mootmaker.model.Person;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

import module java.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateMissingPersonsRepairTest {

    private static final String TABLE_NAME = "People";
    private static final String USER_POOL_ID = "pool-1";

    private static UserType user(final String username, final String sub, final String email, final UserStatusType status) {
        return UserType.builder()
                .username(username)
                .userStatus(status)
                .attributes(
                        AttributeType.builder().name("sub").value(sub).build(),
                        AttributeType.builder().name("email").value(email).build())
                .build();
    }

    private static UserType confirmedUser(final String username, final String sub, final String email) {
        return user(username, sub, email, UserStatusType.CONFIRMED);
    }

    @Test
    void createsAPersonNamedAfterTheEmailLocalPartForAUserWithNoLinkedPerson() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.add(confirmedUser("u1", "sub-1", "ada.lovelace@example.com"));
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, USER_POOL_ID, TABLE_NAME, false);

        assertEquals(1, result.repaired());
        assertEquals(0, result.alreadyLinked());
        final List<Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> created =
                dynamoDbClient.tables.get(TABLE_NAME);
        assertEquals(1, created.size());
        assertEquals("ada.lovelace", created.getFirst().get("name").s());
        assertEquals("sub-1", created.getFirst().get("cognitoSub").s());
    }

    @Test
    void skipsAUserThatAlreadyHasALinkedPerson() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.add(confirmedUser("u1", "sub-1", "ada@example.com"));
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();
        dynamoDbClient.tables.put(TABLE_NAME, new ArrayList<>(List.of(new Person("person-1", "Ada", "sub-1").toItem())));

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, USER_POOL_ID, TABLE_NAME, false);

        assertEquals(0, result.repaired());
        assertEquals(1, result.alreadyLinked());
        assertEquals(1, dynamoDbClient.tables.get(TABLE_NAME).size(), "must not create a duplicate Person");
    }

    @Test
    void ignoresUnconfirmedUsers() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.add(user("u1", "sub-1", "pending@example.com", UserStatusType.UNCONFIRMED));
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, USER_POOL_ID, TABLE_NAME, false);

        assertEquals(0, result.repaired());
        assertEquals(0, result.alreadyLinked());
        assertTrue(dynamoDbClient.tables.getOrDefault(TABLE_NAME, List.of()).isEmpty());
    }

    @Test
    void dryRunReportsWithoutWritingAnything() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.add(confirmedUser("u1", "sub-1", "ada@example.com"));
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, USER_POOL_ID, TABLE_NAME, true);

        assertEquals(1, result.repaired());
        assertTrue(dynamoDbClient.tables.getOrDefault(TABLE_NAME, List.of()).isEmpty(), "dry run must not write anything");
    }

    @Test
    void followsPaginationAcrossMultiplePages() {
        final FakeCognitoIdentityProviderClient cognitoClient = new FakeCognitoIdentityProviderClient();
        cognitoClient.users.addAll(List.of(
                confirmedUser("u1", "sub-1", "one@example.com"),
                confirmedUser("u2", "sub-2", "two@example.com")));
        cognitoClient.listUsersPageSize = 1;
        final FakeDynamoDbClient dynamoDbClient = new FakeDynamoDbClient();

        final CreateMissingPersonsRepair.Result result =
                CreateMissingPersonsRepair.run(cognitoClient, dynamoDbClient, USER_POOL_ID, TABLE_NAME, false);

        assertEquals(2, result.repaired());
        assertEquals(2, dynamoDbClient.tables.get(TABLE_NAME).size());
    }

    @Test
    void emailLocalPartHandlesAnEmailWithNoAtSign() {
        assertEquals("not-an-email", CreateMissingPersonsRepair.emailLocalPart("not-an-email"));
        assertEquals("ada", CreateMissingPersonsRepair.emailLocalPart("ada@example.com"));
    }
}
