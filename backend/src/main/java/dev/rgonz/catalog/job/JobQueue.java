package dev.rgonz.catalog.job;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * The queue that carries the jobs' messages: Amazon SQS, or a stand-in that speaks as it does. A
 * message's body names its job, and the job's type travels beside it as an attribute.
 */
@Component
@TalksToTheQueue
class JobQueue {
  /** The most messages one request receives, which is the most SQS gives at once. */
  static final int MOST_AT_ONCE = 10;

  /** How SQS names the place a queue is at: by its region. */
  private static final Pattern AMAZON = Pattern.compile("sqs\\.([a-z0-9-]+)\\.amazonaws\\.com");

  private final SqsClient sqs;
  private final String url;

  JobQueue(@Value("${app.jobs.queue-url}") String url) {
    this.url = url;
    this.sqs = client(URI.create(url));
  }

  /**
   * A client for where the queue is. A queue of Amazon's is in the region its address names, and is
   * reached as whoever the process runs as. Any other address is a stand-in, which is reached where
   * it is and asks for no one's name.
   */
  private static SqsClient client(URI queue) {
    var amazon = AMAZON.matcher(queue.getHost());
    if (amazon.matches()) {
      return SqsClient.builder().region(Region.of(amazon.group(1))).build();
    }
    return SqsClient.builder()
        .region(Region.US_EAST_1)
        .endpointOverride(URI.create(queue.getScheme() + "://" + queue.getAuthority()))
        .credentialsProvider(AnonymousCredentialsProvider.create())
        .build();
  }

  @PreDestroy
  void close() {
    sqs.close();
  }

  void send(String body, String type) {
    sqs.sendMessage(
        message ->
            message
                .queueUrl(url)
                .messageBody(body)
                .messageAttributes(
                    Map.of(
                        "type",
                        MessageAttributeValue.builder()
                            .dataType("String")
                            .stringValue(type)
                            .build())));
  }

  /**
   * What waits on the queue, or what arrives while this waits for it. What it answers with stays
   * hidden from other receivers for the time the queue sets, and is delivered again unless it is
   * deleted by then.
   */
  List<Message> receive(Duration wait) {
    return sqs.receiveMessage(
            request ->
                request
                    .queueUrl(url)
                    .maxNumberOfMessages(MOST_AT_ONCE)
                    .waitTimeSeconds((int) wait.toSeconds()))
        .messages();
  }

  /** Removes every message, the hidden ones too. Only tests have a use for it. */
  void empty() {
    sqs.purgeQueue(request -> request.queueUrl(url));
  }

  void delete(Message message) {
    sqs.deleteMessage(request -> request.queueUrl(url).receiptHandle(message.receiptHandle()));
  }
}
