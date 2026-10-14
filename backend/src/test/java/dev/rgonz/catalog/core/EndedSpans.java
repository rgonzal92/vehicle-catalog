package dev.rgonz.catalog.core;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/**
 * Keeps every span the application ends, for the tests that look at what a trace holds. Spring Boot
 * hands every {@code SpanProcessor} bean to the tracing SDK, so this one is told of each span as it
 * ends, on whatever thread ends it, with nothing sent anywhere.
 */
@Component
public class EndedSpans implements SpanProcessor {
  private final List<SpanData> ended = new CopyOnWriteArrayList<>();

  /** The spans ended so far, in the order they ended. */
  public List<SpanData> ended() {
    return List.copyOf(ended);
  }

  @Override
  public void onStart(Context parent, ReadWriteSpan span) {}

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    ended.add(span.toSpanData());
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }
}
