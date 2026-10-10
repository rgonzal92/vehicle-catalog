package dev.rgonz.catalog.core;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** How the app reaches Amazon S3, or a stand-in that speaks as it does. */
public final class Storage {
  /**
   * What a stand-in is told about who asks. It asks for a key and checks nothing; without one the
   * client would look for the credentials of whoever the process runs as.
   */
  public static final StaticCredentialsProvider NO_ONE_IN_PARTICULAR =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("stand-in", "stand-in"));

  private Storage() {}

  /**
   * A client for the buckets.
   *
   * @param endpoint where a stand-in is, or nothing for Amazon S3 itself, which is reached as
   *     whoever the process runs as
   */
  public static S3Client client(String endpoint) {
    var builder = S3Client.builder();
    if (!endpoint.isBlank()) {
      builder
          .endpointOverride(URI.create(endpoint))
          .region(Region.US_EAST_1)
          .credentialsProvider(NO_ONE_IN_PARTICULAR)
          .forcePathStyle(true);
    }
    return builder.build();
  }
}
