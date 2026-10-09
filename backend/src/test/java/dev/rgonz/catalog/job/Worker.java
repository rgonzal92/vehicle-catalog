package dev.rgonz.catalog.job;

import java.time.Duration;
import java.util.List;
import org.springframework.context.ApplicationContext;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * Stands in for the worker in tests, where nothing sends or receives a message by itself: a test
 * has each of the worker's steps done when it wants it done, and can stop between two of them as a
 * worker that is stopped does.
 */
public final class Worker {
  private Worker() {}

  /** Does what the worker does, once: sends what is unsent, and runs the jobs that wait. */
  public static void runs(ApplicationContext application) {
    sends(application);
    application.getBean(Consumer.class).receive(Duration.ZERO);
  }

  /** Sends the unsent messages and does nothing else, and answers with how many there were. */
  public static int sends(ApplicationContext application) {
    return application.getBean(Publisher.class).sendUnsent();
  }

  /**
   * Receives what waits on the queue and then stops, as a worker that is stopped in the middle of a
   * job does: nothing is run and nothing is deleted, so the queue delivers the messages again.
   *
   * @return the bodies of the messages it received
   */
  public static List<String> takesAndStops(ApplicationContext application) {
    return application.getBean(JobQueue.class).receive(Duration.ZERO).stream()
        .map(Message::body)
        .toList();
  }

  /** Leaves no job and no message, in the database or on the queue, for the test that follows. */
  public static void forgets(ApplicationContext application) {
    application
        .getBean(org.springframework.jdbc.core.simple.JdbcClient.class)
        .sql("DELETE FROM job")
        .update();
    application.getBean(JobQueue.class).empty();
  }
}
