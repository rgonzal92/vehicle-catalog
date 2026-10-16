package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.core.EndedSpans;
import dev.rgonz.catalog.core.Role;
import io.micrometer.core.instrument.Meter;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the steps inside a request leave spans of their own in the request's trace, each
 * under the request's span and naming its catalog, and that none of them adds a metric. Every test
 * starts from the seeded catalogs.
 */
class CatalogStepsIT extends WorkingCopyTests {
  private static final AttributeKey<String> CATALOG_ID = AttributeKey.stringKey("catalog.id");

  @Autowired EndedSpans spans;

  /** The seeded Compact SUV 2026, at its second version. */
  private long approved;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
    approved = approved("COMPACT_SUV", 2026, 2);
  }

  @Test
  void openingACatalogReadsItReadsTheLibraryAndWorksOutItsIssuesUnderTheRequest() {
    assertThat(open(ana(), approved)).hasStatusOk();

    var trace = traceOf("catalog.read", approved);
    assertThat(steps(trace)).containsExactly("catalog.read", "catalog.library", "catalog.issues");
    assertThat(stepsOf(trace)).allSatisfy(step -> assertUnderTheRequest(step, trace, approved));
  }

  @Test
  void savingACellSavesAndThenReadsForTheAnswerUnderTheRequest() {
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);

    assertThat(setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .hasStatusOk();

    var trace = traceOf("catalog.save", copy);
    assertThat(steps(trace))
        .containsExactly("catalog.save", "catalog.read", "catalog.library", "catalog.issues");
    assertThat(stepsOf(trace)).allSatisfy(step -> assertUnderTheRequest(step, trace, copy));
    assertThat(stepsOf(trace))
        .extracting(step -> step.getStatus().getStatusCode())
        .doesNotContain(StatusCode.ERROR);
  }

  @Test
  void deletingAWorkingCopyIsAStepUnderTheRequest() {
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);

    assertThat(edit(ana(), mvc.delete().uri("/api/catalogs/{id}", copy), "\"0\"", null))
        .hasStatus(204);

    var trace = traceOf("catalog.save", copy);
    assertThat(steps(trace)).containsExactly("catalog.save");
    assertUnderTheRequest(stepsOf(trace).getFirst(), trace, copy);
  }

  @Test
  void aNewWorkingCopyCopiesItsStartingPointUnderTheRequest() {
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);

    var trace = traceOf("catalog.contents.copy", approved);
    assertThat(steps(trace)).containsExactly("catalog.contents.copy");
    assertUnderTheRequest(stepsOf(trace).getFirst(), trace, approved);
    assertThat(copy).isNotEqualTo(approved);
  }

  @Test
  void aRefusedSaveLeavesItsStepMarkedAsFailed() {
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A"));

    assertThat(setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "S")))
        .hasStatus(412);

    var trace = traceOf("catalog.save", copy);
    assertThat(steps(trace)).containsExactly("catalog.save");
    var save = stepsOf(trace).getFirst();
    assertUnderTheRequest(save, trace, copy);
    assertThat(save.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
  }

  @Test
  void noStepAddsAMetricOrASecondSeriesToATimer() {
    assertThat(open(ana(), approved)).hasStatusOk();
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    assertThat(setCells(ana(), copy, "\"0\"", cell("TRANS_MANUAL", "Base", "NA", "A")))
        .hasStatusOk();

    var kept = meters.getMeters().stream().map(Meter::getId).map(Meter.Id::getName).distinct();
    assertThat(kept.toList())
        .contains(
            "health",
            "http.server.requests",
            "jvm.heap.used",
            "catalog.edit",
            "catalog.copy",
            "catalog.validate",
            "catalog.submit.refused",
            "catalog.approved",
            "catalog.rejected",
            "catalog.merged",
            "cache.read",
            "ai.spent")
        .filteredOn(name -> !name.startsWith("ai."))
        .hasSize(11);
    for (var timer : List.of("catalog.validate", "catalog.edit", "catalog.copy")) {
      assertThat(meters.find(timer).meters())
          .as("%s is one timer, told apart by nothing", timer)
          .singleElement()
          .satisfies(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }
  }

  @Test
  void aSubmitAndAnApprovalEachWorkOutTheCatalogsIssuesOnce() {
    var copy = workingCopy(ana(), "COMPACT_SUV", 2026);

    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), "\"0\"", "{}"))
        .hasStatusOk();
    assertThat(steps(traceOf("catalog.save", copy)))
        .as("the steps of the submit")
        .containsOnlyOnce("catalog.issues");

    assertThat(edit(mia(), mvc.post().uri("/api/catalogs/{id}/approve", copy), "\"1\"", "{}"))
        .hasStatusOk();
    assertThat(steps(traceOf("catalog.save", copy)))
        .as("the steps of the approval")
        .containsOnlyOnce("catalog.issues");
  }

  @Test
  void anExportRequestADiffASummaryAndAnUpdatePreviewWorkOutNoIssues() {
    var anas = workingCopy(ana(), "COMPACT_SUV", 2026);
    var bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", anas), "\"0\"", "{}"))
        .hasStatusOk();

    assertThat(mvc.get().uri("/api/catalogs/{id}/summary", anas).with(mia())).hasStatusOk();
    assertThat(steps(traceOf("catalog.read", anas)))
        .as("the steps of a summary")
        .doesNotContain("catalog.issues");

    assertThat(edit(mia(), mvc.post().uri("/api/catalogs/{id}/approve", anas), "\"1\"", "{}"))
        .hasStatusOk();

    assertThat(edit(ben(), mvc.post().uri("/api/catalogs/{id}/exports", bens), null, null))
        .hasStatus2xxSuccessful();
    assertThat(steps(traceOf("catalog.read", bens)))
        .as("the steps of an export request")
        .doesNotContain("catalog.issues");

    assertThat(mvc.get().uri("/api/catalogs/{id}/diff?against=base", bens).with(ben()))
        .hasStatusOk();
    assertThat(steps(traceOf("catalog.read", bens)))
        .as("the steps of a diff")
        .doesNotContain("catalog.issues");

    assertThat(edit(ben(), mvc.post().uri("/api/catalogs/{id}/merge-preview", bens), null, null))
        .hasStatusOk();
    assertThat(steps(traceOf("catalog.read", bens)))
        .as("the steps of an update preview")
        .doesNotContain("catalog.issues");
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  /**
   * The spans of the trace that holds the last step of the name about the catalog, in the order
   * they began.
   */
  private List<SpanData> traceOf(String step, long catalog) {
    var ended = spans.ended();
    var found =
        ended.reversed().stream()
            .filter(span -> span.getName().equals(step) && catalogOf(span).equals(catalog))
            .findFirst()
            .orElseThrow();
    return ended.stream()
        .filter(span -> span.getTraceId().equals(found.getTraceId()))
        .sorted(Comparator.comparingLong(SpanData::getStartEpochNanos))
        .toList();
  }

  private static List<SpanData> stepsOf(List<SpanData> trace) {
    return trace.stream().filter(span -> span.getName().startsWith("catalog.")).toList();
  }

  private static List<String> steps(List<SpanData> trace) {
    return stepsOf(trace).stream().map(SpanData::getName).toList();
  }

  private static Long catalogOf(SpanData span) {
    var id = span.getAttributes().get(CATALOG_ID);
    return id == null ? null : Long.valueOf(id);
  }

  /** The step's parent is the request's span, which is the root of the trace. */
  private static void assertUnderTheRequest(SpanData step, List<SpanData> trace, long catalog) {
    var request = trace.getFirst();
    assertThat(request.getParentSpanContext().isValid()).as("the request's span").isFalse();
    assertThat(request.getName()).startsWith("http ");
    assertThat(step.getParentSpanId()).isEqualTo(request.getSpanId());
    assertThat(catalogOf(step)).isEqualTo(catalog);
  }
}
