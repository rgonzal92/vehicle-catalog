package dev.rgonz.catalog.catalog;

import io.lettuce.core.resource.Delay;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.ClientResourcesBuilderCustomizer;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Where the cache is, as Spring's Redis support is told it, and how often the client looks for a
 * cache that has stopped. An address that is not set, or is set to nothing, is no address: Spring
 * is then told of a cache on this machine, which it does not look for, and the app never asks it.
 */
@Configuration(proxyBeanMethods = false)
class CacheAddress {
  @Bean
  DataRedisConnectionDetails whereTheCacheIs(
      @Value("${app.cache.host}") String host,
      @Value("${app.cache.port}") int port,
      @Value("${app.cache.password}") String password) {
    return new DataRedisConnectionDetails() {
      @Override
      public String getPassword() {
        return password.isEmpty() ? null : password;
      }

      @Override
      public Standalone getStandalone() {
        return Standalone.of(host.isBlank() ? "localhost" : host, port);
      }
    };
  }

  /**
   * Has the client try to reach a cache that has stopped every two seconds. Left to itself it waits
   * longer after every try, up to half a minute, and a cache that had been gone for a while would
   * be back for that long before the app could find it.
   */
  @Bean
  ClientResourcesBuilderCustomizer looksForAStoppedCacheEveryTwoSeconds() {
    return client -> client.reconnectDelay(Delay.constant(Duration.ofSeconds(2)));
  }
}
