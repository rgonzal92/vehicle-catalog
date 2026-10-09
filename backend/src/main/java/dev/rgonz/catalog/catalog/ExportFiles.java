package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.core.ApiException;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Where the exported spreadsheets are kept: a bucket in Amazon S3, or in a stand-in that speaks as
 * it does. The worker puts a file there, and the API hands out a link to it that works for a few
 * minutes. Neither reaches the bucket before it has a file to put or a link to make.
 */
@Component
class ExportFiles {
  /** How long a link to a file works. */
  static final Duration LINK_LASTS = Duration.ofMinutes(5);

  private static final String SPREADSHEET =
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

  private final String bucket;
  private final String endpoint;
  private S3Client s3;
  private S3Presigner presigner;

  ExportFiles(
      @Value("${app.exports.bucket}") String bucket,
      @Value("${app.exports.endpoint}") String endpoint) {
    this.bucket = bucket;
    this.endpoint = endpoint;
  }

  @PreDestroy
  synchronized void close() {
    if (s3 != null) {
      s3.close();
    }
    if (presigner != null) {
      presigner.close();
    }
  }

  /**
   * Keeps a file under a key, in place of any that is there under it, with the name a browser is to
   * save it under.
   */
  void put(String key, byte[] file, String fileName) {
    s3().putObject(
            request ->
                request
                    .bucket(bucket)
                    .key(key)
                    .contentType(SPREADSHEET)
                    .contentDisposition("attachment; filename=\"%s\"".formatted(fileName)),
            RequestBody.fromBytes(file));
  }

  /**
   * A link to the file under a key, which whoever is given it can read the file with for a while.
   */
  URI linkTo(String key) {
    return URI.create(
        presigner()
            .presignGetObject(
                link ->
                    link.signatureDuration(LINK_LASTS)
                        .getObjectRequest(file -> file.bucket(bucket).key(key)))
            .url()
            .toString());
  }

  /** Removes every file. Without a bucket there is none to remove. */
  void deleteAll() {
    if (bucket.isBlank()) {
      return;
    }
    s3().listObjectsV2Paginator(request -> request.bucket(bucket)).contents().stream()
        .forEach(file -> s3().deleteObject(request -> request.bucket(bucket).key(file.key())));
  }

  private synchronized S3Client s3() {
    requireABucket();
    if (s3 == null) {
      var builder = S3Client.builder();
      if (!endpoint.isBlank()) {
        builder
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(NO_ONE_IN_PARTICULAR)
            .forcePathStyle(true);
      }
      s3 = builder.build();
    }
    return s3;
  }

  private synchronized S3Presigner presigner() {
    requireABucket();
    if (presigner == null) {
      var builder = S3Presigner.builder();
      if (!endpoint.isBlank()) {
        builder
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(NO_ONE_IN_PARTICULAR)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
      }
      presigner = builder.build();
    }
    return presigner;
  }

  /**
   * Who a stand-in is reached as. It asks for a name and checks none; Amazon S3 is reached as
   * whoever the process runs as.
   */
  private static final StaticCredentialsProvider NO_ONE_IN_PARTICULAR =
      StaticCredentialsProvider.create(AwsBasicCredentials.create("stand-in", "stand-in"));

  private void requireABucket() {
    if (bucket.isBlank()) {
      throw ApiException.unavailable(
          "EXPORTS_UNAVAILABLE", "This installation has nowhere to keep an exported spreadsheet.");
    }
  }
}
