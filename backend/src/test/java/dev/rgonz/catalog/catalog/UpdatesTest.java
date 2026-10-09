package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rgonz.catalog.catalog.CatalogSnapshot.Status;
import dev.rgonz.catalog.catalog.CatalogSnapshot.Trim;
import dev.rgonz.catalog.core.ApiException;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Checks that an update is refused when it would make a catalog larger than a catalog can be. */
class UpdatesTest {
  private static CatalogSnapshot withTrims(int howMany) {
    var trims = IntStream.rangeClosed(1, howMany).mapToObj(id -> new Trim(id, "Trim " + id, id));

    return new CatalogSnapshot(
        41,
        3,
        Status.DRAFT,
        0,
        trims.toList(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  @Test
  void aMergedCatalogWithMoreTrimsThanACatalogHasIsRefused() {
    assertThatCode(() -> Updates.requireWithinLimits(withTrims(CatalogEdits.MOST_TRIMS)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> Updates.requireWithinLimits(withTrims(CatalogEdits.MOST_TRIMS + 1)))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("LIMIT_EXCEEDED");
  }
}
