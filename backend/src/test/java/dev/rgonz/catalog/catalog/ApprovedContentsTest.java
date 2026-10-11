package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.rgonz.catalog.catalog.ApprovedContents.Contents;
import dev.rgonz.catalog.catalog.ApprovedContents.Counts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Checks when a read of an Approved version's contents asks the cache at all. */
class ApprovedContentsTest {
  private static final Contents FROM_THE_DATABASE =
      new Contents(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

  @Test
  void aReadDoesNotAskACacheThatIsNotKnownToAnswer() {
    var asked = new AtomicInteger();
    var cache =
        new StringRedisTemplate() {
          @Override
          public ValueOperations<String, String> opsForValue() {
            asked.incrementAndGet();
            throw new IllegalStateException("Nothing has reached this cache yet.");
          }
        };
    var contents = new ApprovedContents("cache", cache, new SimpleMeterRegistry());

    var read = contents.of(7, () -> FROM_THE_DATABASE);

    assertThat(read).isSameAs(FROM_THE_DATABASE);
    assertThat(asked)
        .as("how often the read asked the cache, which it would have had to reach first")
        .hasValue(0);
    assertThat(contents.counts()).isEqualTo(new Counts(0, 0, 1));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aCacheThatIsSlowToAnswerTheFirstTimeItIsAskedIsKnownToAnswerOnceTheAppHasStarted() {
    var asked = new AtomicInteger();
    var values = mock(ValueOperations.class);
    var cache =
        new StringRedisTemplate() {
          @Override
          public <T> T execute(RedisCallback<T> ping) {
            if (asked.incrementAndGet() == 1) {
              throw new QueryTimeoutException("Redis command timed out");
            }
            return null;
          }

          @Override
          public ValueOperations<String, String> opsForValue() {
            return values;
          }
        };
    var contents = new ApprovedContents("cache", cache, new SimpleMeterRegistry());

    contents.asksAtTheStart();
    contents.of(7, () -> FROM_THE_DATABASE);

    assertThat(asked).as("how often the cache was asked whether it is there").hasValue(2);
    assertThat(contents.counts())
        .as("the read that followed asked the cache, which had nothing for it")
        .isEqualTo(new Counts(0, 1, 0));
  }
}
