package dev.rgonz.catalog.job;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
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
  /** The name of the attribute, and of the header elsewhere, that carries a message's trace. */
  static final String TRACE = "traceparent";

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

  /**
   * Sends a message for a job.
   *
   * @param traceparent the trace the job was written in, or null when it was written in none
   */
  void send(String body, String type, String traceparent) {
    var attributes = new HashMap<String, MessageAttributeValue>();
    attributes.put("type", text(type));
    if (traceparent != null) {
      attributes.put(TRACE, text(traceparent));
    }
    sqs.sendMessage(
        message -> message.queueUrl(url).messageBody(body).messageAttributes(attributes));
  }

  private static MessageAttributeValue text(String value) {
    return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
  }

  /** What a message says beside its body: its job's type, and its trace when it has one. */
  static Map<String, String> attributesOf(Message message) {
    var said = new HashMap<String, String>();
    message.messageAttributes().forEach((name, value) -> said.put(name, value.stringValue()));
    return said;
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
                    .messageAttributeNames("All")
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
