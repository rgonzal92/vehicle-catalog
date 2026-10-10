package dev.rgonz.catalog.document;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.Storage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Where the files of uploaded documents are kept: a bucket in Amazon S3, or in a stand-in that
 * speaks as it does. A file is kept under its document's id, and is never handed out again.
 */
@Component
class DocumentFiles {
  private final String bucket;
  private final String endpoint;
  private S3Client s3;

  DocumentFiles(
      @Value("${app.documents.bucket}") String bucket,
      @Value("${app.documents.endpoint}") String endpoint) {
    this.bucket = bucket;
    this.endpoint = endpoint;
  }

  @PreDestroy
  synchronized void close() {
    if (s3 != null) {
      s3.close();
    }
  }

  /** Keeps a file under a key. */
  void put(String key, byte[] file, String contentType) {
    s3().putObject(
            request -> request.bucket(bucket).key(key).contentType(contentType),
            RequestBody.fromBytes(file));
  }

  /** Removes the file under a key. One that is not there is none to remove. */
  void delete(String key) {
    s3().deleteObject(request -> request.bucket(bucket).key(key));
  }

  /** Removes every file. Without a bucket there is none to remove. */
  void deleteAll() {
    if (bucket.isBlank()) {
      return;
    }
    s3().listObjectsV2Paginator(request -> request.bucket(bucket)).contents().stream()
        .forEach(file -> delete(file.key()));
  }

  private synchronized S3Client s3() {
    if (bucket.isBlank()) {
      throw ApiException.unavailable(
          "DOCUMENTS_UNAVAILABLE", "This installation has nowhere to keep a document.");
    }
    if (s3 == null) {
      s3 = Storage.client(endpoint);
    }
    return s3;
  }
}
