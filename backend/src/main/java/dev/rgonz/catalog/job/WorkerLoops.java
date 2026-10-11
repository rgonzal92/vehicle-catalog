package dev.rgonz.catalog.job;

import io.micrometer.observation.ObservationPredicate;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.stereotype.Component;

/**
 * What the worker does for as long as it runs: it sends the messages that changes left unsent, runs
 * the jobs whose messages arrive, and keeps the dead-letter queue to the jobs that are failed. Only
 * the worker does any of it.
 */
@Component
@Profile("worker")
@TalksToTheQueue
class WorkerLoops {
  /**
   * How long one wait on the queue lasts. A worker that is told to stop ends the wait it is in and
   * starts no other, so it stops within this long, and Docker gives it ten seconds. A job that is
   * running then has what is left of them. Each wait is a request to SQS, so it is no shorter.
   */
  private static final Duration WAIT = Duration.ofSeconds(2);

  private final Publisher publisher;
  private final Consumer consumer;

  WorkerLoops(Publisher publisher, Consumer consumer) {
    this.publisher = publisher;
    this.consumer = consumer;
  }

  /**
   * The loops run every second or so for as long as the worker does, and each run would leave a
   * trace that says nothing. So a run is not observed. The jobs themselves are.
   */
  @Bean
  static ObservationPredicate theLoopsAreNotObserved() {
    return (name, context) ->
        !(context instanceof ScheduledTaskObservationContext run
            && run.getTargetClass() == WorkerLoops.class);
  }

  /** Looks for unsent messages every second, and goes on at once while there are more. */
  @Scheduled(fixedDelayString = "PT1S")
  void publish() {
    while (publisher.sendUnsent() == JobQueue.MOST_AT_ONCE) {
      // A full batch says there may be more.
    }
  }

  /** Clears the dead-letter queue, every minute, of the jobs that are no longer failed. */
  @Scheduled(fixedDelayString = "PT1M")
  void sweep() {
    consumer.sweepTheDeadLetterQueue();
  }

  /**
   * Waits on the queue, one wait after another without a pause: a message is handled as soon as it
   * arrives.
   */
  @Scheduled(fixedDelayString = "PT0.01S")
  void consume() {
    consumer.receive(WAIT);
  }
}
