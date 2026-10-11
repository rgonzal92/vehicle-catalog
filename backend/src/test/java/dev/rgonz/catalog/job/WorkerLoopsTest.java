package dev.rgonz.catalog.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/**
 * Checks that the worker's loops, which run every second or so, leave no trace of each run, and
 * that a wait on the queue is short.
 */
class WorkerLoopsTest {
  /**
   * A worker that is told to stop ends the wait it is in first. So a wait is two seconds at the
   * most. It is a second at the least: SQS waits whole seconds, and asked to wait less than one it
   * would not wait at all, and be asked again at once.
   */
  @Test
  void aWaitOnTheQueueIsShortEnoughToStopAfterAndLongEnoughToBeAWait() {
    var waits = new ArrayList<Duration>();
    var consumer =
        new Consumer(null, null, null, null, null, List.of()) {
          @Override
          int receive(Duration wait) {
            waits.add(wait);
            return 0;
          }
        };

    new WorkerLoops(null, consumer).consume();

    assertThat(waits)
        .singleElement()
        .satisfies(
            wait -> assertThat(wait).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(2)));
  }

  @Test
  void aRunOfALoopIsNotObservedAndAnyOtherScheduledRunIs() throws Exception {
    var observed = WorkerLoops.theLoopsAreNotObserved();
    var loop = new WorkerLoops(null, null);

    assertThat(
            observed.test(
                "tasks.scheduled.execution",
                new ScheduledTaskObservationContext(
                    loop, WorkerLoops.class.getDeclaredMethod("publish"))))
        .isFalse();
    assertThat(
            observed.test(
                "tasks.scheduled.execution",
                new ScheduledTaskObservationContext(this, getClass().getDeclaredMethod("other"))))
        .isTrue();
  }

  void other() {}
}
