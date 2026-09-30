package com.mootmaker.testsupport;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * A real presigner rather than a fake. Presigning is local computation - it sends nothing - so with
 * throwaway credentials it runs in a unit test exactly as it does deployed, and the URL it produces
 * is the genuine article to assert on.
 */
public final class TestPresigner {

  private TestPresigner() {}

  public static S3Presigner create() {
    return S3Presigner.builder()
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test-key", "test-secret")))
        .build();
  }
}
