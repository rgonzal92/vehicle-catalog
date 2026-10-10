package dev.rgonz.catalog.ai;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/**
 * Has the application ask the real model where every test asks a stand-in: at OpenAI's own address,
 * with the key the environment holds as {@code OPENAI_API_KEY}, and with the time the application
 * itself gives the model. It is for measuring the model, which is no part of the build. What is
 * asked goes through the allowance as it does in the application.
 */
@TestConfiguration
public class TheRealModel {
  @Bean
  @Primary
  Model theRealModel(
      @Value("${app.ai.model}") String name,
      ObservationRegistry observations,
      MeterRegistry metrics,
      Allowance allowance,
      JsonMapper json) {
    return new Model(
        Objects.requireNonNullElse(System.getenv("OPENAI_API_KEY"), ""),
        "https://api.openai.com/v1",
        name,
        Duration.ofSeconds(20),
        observations,
        metrics,
        allowance,
        json);
  }
}
