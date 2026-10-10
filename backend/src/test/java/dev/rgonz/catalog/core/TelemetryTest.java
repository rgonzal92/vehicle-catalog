package dev.rgonz.catalog.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Checks which of the model's metrics each process sends: the API all of them, the worker its own.
 */
class TelemetryTest {
  private static final List<String> OF_THE_MODEL =
      List.of("ai.call", "ai.tokens", "ai.tool.calls", "ai.refused", "ai.spent");

  /** The metrics of the model that a process with these profiles keeps. */
  private static List<String> kept(String... profiles) {
    var environment = new MockEnvironment();
    environment.setActiveProfiles(profiles);
    var filter = new Telemetry().onlyTheMetricsThatAreLookedAt(environment);

    return OF_THE_MODEL.stream()
        .filter(
            name ->
                filter.accept(new Meter.Id(name, Tags.empty(), null, null, Meter.Type.OTHER))
                    == MeterFilterReply.ACCEPT)
        .toList();
  }

  @Test
  void theApiSendsEveryMetricOfTheModel() {
    assertThat(kept()).isEqualTo(OF_THE_MODEL);
  }

  @Test
  void theWorkerSendsWhatAJobAsksOfTheModelAndNeitherToolCallsNorWhatTheDayHasCost() {
    assertThat(kept("worker")).containsExactly("ai.call", "ai.tokens", "ai.refused");
  }
}
