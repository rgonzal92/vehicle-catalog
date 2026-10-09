package dev.rgonz.catalog.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

/** Checks that the worker's loops, which run every second or so, leave no trace of each run. */
class WorkerLoopsTest {
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
