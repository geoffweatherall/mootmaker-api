package com.mootmaker.s3;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Lazily-built singletons, reused across warm Lambda invocations to avoid repeated client setup
 * cost - the same shape as {@code CognitoIdentityProviderClientProvider}.
 *
 * <p>Not connection-primed the way {@code DynamoDbClientProvider} is. Every request touches
 * DynamoDB, so its first-call cost is worth paying at INIT and again after every restore; an avatar
 * is set a handful of times in a person's life, which does not justify an S3 round trip on every
 * cold start. Constructing the clients eagerly still puts their classes in the SnapStart snapshot.
 */
public final class S3ClientProvider {

  private static volatile S3Client client;
  private static volatile S3Presigner presigner;

  private S3ClientProvider() {}

  public static S3Client client() {
    if (client == null) {
      synchronized (S3ClientProvider.class) {
        if (client == null) {
          client = S3Client.builder().build();
        }
      }
    }
    return client;
  }

  /**
   * Presigning is local computation - no request is sent - so this holds no connection. It does
   * resolve credentials on every call, which is what keeps a presigned URL valid after a SnapStart
   * restore hands the execution environment fresh ones.
   */
  public static S3Presigner presigner() {
    if (presigner == null) {
      synchronized (S3ClientProvider.class) {
        if (presigner == null) {
          presigner = S3Presigner.create();
        }
      }
    }
    return presigner;
  }
}
