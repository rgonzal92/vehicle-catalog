package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.catalog.ApprovedContents.Counts;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Checks the application whose address for the cache is set to nothing, which is no address: it
 * starts, and it reads Approved versions from the database without asking a cache for them.
 */
@TestPropertySource(properties = "app.cache.host=")
class WithoutACacheIT extends WorkingCopyTests {
  @Autowired ApprovedContents contents;

  @Test
  void theApplicationStartsAndItsReadsCountAsNeitherHitsNorMisses() throws Exception {
    seedLibraryAndCatalogs();
    var approved = approved("COMPACT_SUV", 2026, 2);

    var first = ApplicationIT.<Map<String, Object>>read(open(ana(), approved), "$.snapshot");
    var second = ApplicationIT.<Map<String, Object>>read(open(ana(), approved), "$.snapshot");

    assertThat(second).isEqualTo(first).containsEntry("status", "APPROVED");
    assertThat(contents.counts()).isEqualTo(new Counts(0, 0, 0));
    var health = mvc.get().uri("/api/health").exchange();
    assertThat(health).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("UP");
    assertThat(health)
        .bodyJson()
        .extractingPath("$.components.cache.status")
        .as("the cache, which there is none of")
        .isEqualTo("UNKNOWN");
  }
}
