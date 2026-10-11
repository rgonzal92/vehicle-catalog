package dev.rgonz.catalog.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.catalog.ApprovedContents.Counts;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Worker;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Checks that the contents of an Approved version are read from the cache once it holds them, that
 * nothing else is, and that a cache that does not answer changes nothing a person sees. Each test
 * starts from the seeded catalogs, which the cache holds nothing of: they are new each time.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApprovedContentsIT extends WorkingCopyTests {
  private static final String NOTHING =
      """
      {"trims": [], "regions": [], "offerings": [], "featureRows": [], "cells": [], "rules": []}
      """;

  @Autowired ApprovedContents contents;
  @Autowired StringRedisTemplate cache;
  @Autowired ClientResources client;
  @Autowired ApplicationContext application;

  /** The current Approved version of the seeded Compact SUV 2026. */
  private long approved;

  private Counts before;

  @BeforeEach
  void seededCatalogs() throws Exception {
    seedLibraryAndCatalogs();
    // The jobs the tests before this one and the seed left behind are not this test's to run.
    Worker.forgets(application);
    approved = approved("COMPACT_SUV", 2026, 2);
    before = contents.counts();
  }

  /** Mia, a manager who is on record. */
  private RequestPostProcessor mia() {
    return signedInAs(Role.MANAGER, "mia");
  }

  /** The reads since the test began, as hits, misses, and fallbacks. */
  private List<Long> counted() {
    var now = contents.counts();
    return List.of(
        now.hits() - before.hits(),
        now.misses() - before.misses(),
        now.fallbacks() - before.fallbacks());
  }

  private Map<String, Object> snapshot(MvcTestResult opened) {
    assertThat(opened).hasStatusOk();
    return ApplicationIT.<Map<String, Object>>read(opened, "$.snapshot");
  }

  private String rev(long catalog) {
    return "\"" + revision(catalog) + "\"";
  }

  @Test
  void theFirstReadOfAnApprovedVersionIsAMissAndTheSecondAHitAndBothAnswerTheSame() {
    var reported = meters.get("cache.read").tag("result", "hit").functionCounter();
    var hitsReported = reported.count();
    var first = snapshot(open(ana(), approved));
    assertThat(counted()).as("hits, misses, fallbacks").containsExactly(0L, 1L, 0L);

    var second = snapshot(open(ben(), approved));

    assertThat(counted()).as("hits, misses, fallbacks").containsExactly(1L, 1L, 0L);
    assertThat(reported.count()).as("the hits the API reports").isEqualTo(hitsReported + 1);
    assertThat(second).isEqualTo(first);
    assertThat(ApplicationIT.<List<Object>>read(open(ana(), approved), "$.snapshot.featureRows"))
        .hasSize(151);
    assertThat(cache.getExpire(ApprovedContents.KEY + approved, TimeUnit.SECONDS))
        .as("seconds until the entry is gone: a day from when it was put there")
        .isBetween(86_390L, 86_400L);
  }

  @Test
  void readsOfOneVersionThatArriveTogetherAskTheDatabaseForItOnce() throws Exception {
    var go = new CountDownLatch(1);
    List<Future<Map<String, Object>>> answers;
    try (var readers = Executors.newFixedThreadPool(20)) {
      answers =
          IntStream.range(0, 20)
              .mapToObj(
                  reader ->
                      readers.submit(
                          () -> {
                            go.await();
                            return snapshot(open(ana(), approved));
                          }))
              .toList();
      go.countDown();
    }

    assertThat(counted())
        .as("hits, misses, fallbacks: one read asked the database, and the others waited for it")
        .containsExactly(19L, 1L, 0L);
    for (var answer : answers) {
      assertThat(answer.get()).isEqualTo(answers.getFirst().get());
    }
  }

  @Test
  void theWorkerReadsAnApprovedVersionFromTheCacheTheApiPutItIn() {
    assertThat(edit(ben(), mvc.post().uri("/api/catalogs/{id}/exports", approved), null, null))
        .hasStatus2xxSuccessful();
    assertThat(counted()).as("hits, misses, fallbacks of the API").containsExactly(0L, 1L, 0L);

    Worker.runs(application);

    assertThat(counted()).as("hits, misses, fallbacks with the worker").containsExactly(1L, 1L, 0L);
    assertThat(count("job WHERE type = 'EXPORT' AND status = 'SUCCEEDED'")).isOne();
  }

  @Test
  void aWorkingCopyIsNeverPutIntoTheCacheOrReadFromItWhateverItsStatus() {
    long copy = workingCopy(ana(), "COMPACT_SUV", 2026);
    cache.opsForValue().set(ApprovedContents.KEY + copy, NOTHING);

    var draft = snapshot(open(ana(), copy));
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", copy), rev(copy), "{}"))
        .hasStatusOk();
    var submitted = snapshot(open(mia(), copy));

    assertThat(ApplicationIT.<List<Object>>read(open(ana(), copy), "$.snapshot.featureRows"))
        .as("its feature rows, where the cache holds none under its id")
        .hasSize(151);
    assertThat(submitted.get("featureRows")).isEqualTo(draft.get("featureRows"));
    assertThat(counted()).as("hits, misses, fallbacks").containsExactly(0L, 0L, 0L);
    assertThat(cache.opsForValue().get(ApprovedContents.KEY + copy))
        .as("what the cache held under its id is left as it was")
        .isEqualTo(NOTHING);
  }

  @Test
  void aCatalogThatIsNoLongerThereIsNotFoundAlthoughTheCacheStillHoldsItsContents() {
    snapshot(open(ana(), approved));

    emptyLibraryAndCatalogs();

    assertThat(cache.hasKey(ApprovedContents.KEY + approved)).isTrue();
    assertThat(open(ana(), approved)).hasStatus(404);
  }

  @Test
  void anUpdateFromTheApprovedVersionMergesWhatTheDatabaseHoldsWhateverTheCacheHolds() {
    long anas = workingCopy(ana(), "COMPACT_SUV", 2026);
    long bens = workingCopy(ben(), "COMPACT_SUV", 2026);
    setCells(ana(), anas, rev(anas), cell("TRANS_MANUAL", "Base", "NA", "A"));
    assertThat(edit(ana(), mvc.post().uri("/api/catalogs/{id}/submit", anas), rev(anas), "{}"))
        .hasStatusOk();
    assertThat(edit(mia(), mvc.post().uri("/api/catalogs/{id}/approve", anas), rev(anas), "{}"))
        .hasStatusOk();
    cache.opsForValue().set(ApprovedContents.KEY + anas, NOTHING);
    assertThat(ApplicationIT.<List<Object>>read(open(ben(), anas), "$.snapshot.featureRows"))
        .as("what a read of the newly approved version shows, which is what the cache holds")
        .isEmpty();

    var updated =
        edit(
            ben(),
            mvc.post().uri("/api/catalogs/{id}/merge", bens),
            rev(bens),
            "{\"approvedCatalogId\": %d, \"resolutions\": {}}".formatted(anas));

    assertThat(updated).hasStatusOk();
    var merged = open(ben(), bens);
    assertThat(ApplicationIT.<List<Object>>read(merged, "$.snapshot.featureRows")).hasSize(151);
    assertThat(
            ApplicationIT.<List<String>>read(
                merged,
                "$.snapshot.cells[?(@.featureId == %d && @.trimId == %d && @.regionCode == 'NA')]"
                        .formatted(feature("TRANS_MANUAL"), trim("Base"))
                    + ".availability"))
        .as("the change that was approved, which the database holds and the cache does not")
        .containsExactly("A");
  }

  @Test
  void anEntryThatCannotBeReadCountsAsAMissAndIsReplaced() {
    for (var unreadable : List.of("not JSON at all", "{\"trims\": []}")) {
      before = contents.counts();
      cache.opsForValue().set(ApprovedContents.KEY + approved, unreadable);

      var opened = open(ana(), approved);

      assertThat(ApplicationIT.<List<Object>>read(opened, "$.snapshot.featureRows")).hasSize(151);
      assertThat(counted()).as("hits, misses, fallbacks").containsExactly(0L, 1L, 0L);
      assertThat(cache.opsForValue().get(ApprovedContents.KEY + approved))
          .as("the entry after the read")
          .startsWith("{\"trims\":[{")
          .contains("\"rules\":[{");
    }
  }

  @Test
  void withTheCacheStoppedEveryReadAnswersAsBeforeAndHealthSaysSoAndTheAppIsReady(
      CapturedOutput output) {
    var first = snapshot(open(ana(), approved));
    assertThat(mvc.get().uri("/api/health"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.components.cache.status")
        .isEqualTo("UP");

    whileTheCacheDoesNotAnswer(
        () -> {
          for (int read = 0; read < 3; read++) {
            assertThat(snapshot(open(ana(), approved))).isEqualTo(first);
          }
          assertThat(snapshot(open(ana(), approved("SEDAN", 2027, 1))))
              .as("a version the cache was never given")
              .containsEntry("status", "APPROVED");

          assertThat(counted()).as("hits, misses, fallbacks").containsExactly(0L, 1L, 4L);
          assertThat(mvc.get().uri("/api/health/readiness"))
              .hasStatusOk()
              .bodyJson()
              .extractingPath("$.status")
              .isEqualTo("UP");
          assertThat(mvc.get().uri("/api/health"))
              .hasStatus(503)
              .bodyJson()
              .extractingPath("$.components.cache.status")
              .isEqualTo("DOWN");
        });

    assertThat(mvc.get().uri("/api/health"))
        .hasStatusOk()
        .bodyJson()
        .extractingPath("$.components.cache.status")
        .isEqualTo("UP");
    assertThat(snapshot(open(ana(), approved))).isEqualTo(first);
    assertThat(counted()).as("hits, misses, fallbacks").containsExactly(1L, 1L, 4L);
    assertThat(output.getOut().split("The cache stopped answering", -1))
        .as("the log says once that the cache stopped answering")
        .hasSize(2);
    // An app that started within this test said at its start that the cache answers.
    var sinceTheStop =
        output.getOut().substring(output.getOut().indexOf("The cache stopped answering"));
    assertThat(sinceTheStop.split("The cache answers", -1))
        .as("and once, after that, that it answers")
        .hasSize(2);
  }

  @Test
  void aCacheThatHasStoppedIsLookedForEveryTwoSecondsHoweverLongItHasBeenGone() {
    assertThat(List.of(1, 20, 200))
        .as("tries to reach the cache again, by how many there have been")
        .allSatisfy(
            tries ->
                assertThat(client.reconnectDelay().createDelay(tries))
                    .isEqualTo(Duration.ofSeconds(2)));
  }

  @Test
  void aCacheThatAnswersAgainIsUsedAgainWithoutAnyoneAskingForTheAppsHealth() {
    snapshot(open(ana(), approved));
    CACHE.getDockerClient().pauseContainerCmd(CACHE.getContainerId()).exec();
    try {
      snapshot(open(ana(), approved));
      assertThat(counted()).as("hits, misses, fallbacks").containsExactly(0L, 1L, 1L);
    } finally {
      CACHE.getDockerClient().unpauseContainerCmd(CACHE.getContainerId()).exec();
    }

    // The app asks the cache again every ten seconds while it is not known to answer.
    await()
        .atMost(Duration.ofSeconds(15))
        .pollInterval(Duration.ofMillis(500))
        .untilAsserted(
            () -> {
              snapshot(open(ana(), approved));
              assertThat(counted().getFirst()).as("hits").isPositive();
            });
  }
}
