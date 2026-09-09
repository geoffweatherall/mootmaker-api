package com.mootmaker.handler;

import com.mootmaker.concurrent.ConcurrencyUtils;
import com.mootmaker.dynamo.PersonRepository;
import com.mootmaker.model.Person;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminUpdateUserAttributesRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserStatusType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import module java.base;

/**
 * Repair #1: every confirmed Cognito user should have a linked Person, created automatically by
 * {@link PostConfirmationCreatePersonHandler} on sign-up - but users created directly (e.g. the
 * demo user and e2e test user, both admin-created via Terraform rather than through sign-up) skip
 * that trigger entirely, and the trigger itself deliberately swallows its own failures rather than
 * retrying. This finds every confirmed Cognito user whose {@code custom:personId} claim is
 * missing, or points at a Person that no longer exists, and creates one, named after the part of their email before the "@" - a reasonable one-off
 * backfill default, <b>not</b> a re-implementation of the trigger's own behaviour (which uses the
 * Cognito {@code name} attribute, unavailable here for users - like the demo/e2e ones - who never
 * had one set).
 */
final class CreateMissingPersonsRepair {

    /** Cognito's maximum page size per ListUsers call. */
    private static final int LIST_USERS_PAGE_SIZE = 60;

    private CreateMissingPersonsRepair() {
    }

    record Result(int repaired, int alreadyLinked) {
    }

    static Result run(final CognitoIdentityProviderClient cognitoClient, final DynamoDbClient dynamoDbClient,
            final String userPoolId, final String peopleTableName, final boolean dryRun) {
        final List<UserType> users = listConfirmedUsers(cognitoClient, userPoolId);
        System.out.println("Found " + users.size() + " confirmed Cognito user(s).");

        // Each user's check-and-create is independent of every other user's, so they run on the shared bounded thread pool rather than one at a time -
        // the DynamoDB SDK client is safe to share across threads.
        final AtomicInteger repaired = new AtomicInteger();
        final AtomicInteger alreadyLinked = new AtomicInteger();
        ConcurrencyUtils.runInParallel(users, user -> {
            final String cognitoSub = requireAttribute(user, "sub");
            final String email = requireAttribute(user, "email");
            final PersonRepository people = new PersonRepository(dynamoDbClient, peopleTableName);
            final Optional<String> claimedPersonId = optionalAttribute(user, Identity.PERSON_ID_CLAIM);

            // Three states, and the middle one is the reason the trigger sets the claim before writing
            // the Person: a claim pointing at a Person that does not exist is repairable EXACTLY, using
            // the id already on the token. The alternative ordering leaves a Person nothing can find,
            // and this repair would create a duplicate it has no way to detect.
            if (claimedPersonId.isPresent() && people.findById(claimedPersonId.get()).isPresent()) {
                alreadyLinked.incrementAndGet();
                return;
            }

            final String personId = claimedPersonId.orElseGet(() -> UUID.randomUUID().toString());
            final String name = emailLocalPart(email);
            final String what = claimedPersonId.isPresent()
                    ? "claim points at a missing Person, recreating it as '" + name + "'"
                    : "creating Person '" + name + "'";
            System.out.println("  " + email + " -> " + what + (dryRun ? " (dry run)" : ""));

            if (!dryRun) {
                if (claimedPersonId.isEmpty()) {
                    // Claim first, matching the trigger, so a failure here cannot strand a Person.
                    cognitoClient.adminUpdateUserAttributes(AdminUpdateUserAttributesRequest.builder()
                            .userPoolId(userPoolId)
                            .username(cognitoSub)
                            .userAttributes(AttributeType.builder()
                                    .name(Identity.PERSON_ID_CLAIM).value(personId).build())
                            .build());
                }
                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(peopleTableName)
                        .item(new Person(personId, name, cognitoSub).toItem())
                        .build());
            }
            repaired.incrementAndGet();
        });
        return new Result(repaired.get(), alreadyLinked.get());
    }

    private static Optional<String> optionalAttribute(final UserType user, final String name) {
        return user.attributes().stream()
                .filter(attribute -> attribute.name().equals(name))
                .map(AttributeType::value)
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    /** The part of an email address before the "@"; the email itself if there's no "@". */
    static String emailLocalPart(final String email) {
        final int atIndex = email.indexOf('@');
        return atIndex >= 0 ? email.substring(0, atIndex) : email;
    }

    /**
     * Excludes UNCONFIRMED users: they haven't finished sign-up (never went through, and aren't
     * expected to have gone through, the PostConfirmation trigger), so it would be wrong to treat
     * them as needing a repair.
     */
    private static List<UserType> listConfirmedUsers(final CognitoIdentityProviderClient client, final String userPoolId) {
        final List<UserType> users = new ArrayList<>();
        String paginationToken = null;
        do {
            final ListUsersResponse response = client.listUsers(ListUsersRequest.builder()
                    .userPoolId(userPoolId)
                    .limit(LIST_USERS_PAGE_SIZE)
                    .paginationToken(paginationToken)
                    .build());
            response.users().stream()
                    .filter(user -> user.userStatus() != UserStatusType.UNCONFIRMED)
                    .forEach(users::add);
            paginationToken = response.paginationToken();
        } while (paginationToken != null && !paginationToken.isEmpty());
        return users;
    }

    private static String requireAttribute(final UserType user, final String attributeName) {
        return user.attributes().stream()
                .filter(attribute -> attributeName.equals(attribute.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Cognito user '" + user.username() + "' has no '" + attributeName + "' attribute"));
    }
}
