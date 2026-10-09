package dev.rgonz.catalog.library;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Checks which changes of the library increase its revision: the ones that can break a catalog,
 * each by one, and no other. Each test starts from the seeded library.
 */
class LibraryRevisionIT extends ApplicationIT {
  @Autowired LibraryRevision revision;

  @BeforeEach
  void seededLibrary() throws Exception {
    seedLibraryAndCatalogs();
  }

  @Test
  void retiringAndReactivatingAFeatureEachIncreaseItByOneAndRenamingItDoesNot() {
    var feature = id("feature", "code", "FLOOR_CARPET_MATS");
    var before = revision.current();

    assertThat(send(mvc.post().uri("/api/features/{id}/retire", feature), null)).hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 1);
    assertThat(send(mvc.post().uri("/api/features/{id}/retire", feature), null))
        .as("retiring what is retired")
        .hasStatusOk();
    assertThat(revision.current()).as("which changes nothing").isEqualTo(before + 1);
    assertThat(send(mvc.post().uri("/api/features/{id}/reactivate", feature), null)).hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 2);

    var version =
        jdbc.sql("SELECT version FROM feature WHERE id = :id")
            .param("id", feature)
            .query(Long.class)
            .single();
    assertThat(
            send(
                mvc.put().uri("/api/features/{id}", feature),
                """
                {"name": "Floor Mats, Carpeted", "description": "Renamed", "categoryCode": "INTERIOR",
                 "version": %d}
                """
                    .formatted(version)))
        .hasStatusOk();
    assertThat(revision.current()).as("after a new name").isEqualTo(before + 2);
  }

  @Test
  void deactivatingAndActivatingATrimAndARegionEachIncreaseItByOneAndMovingOrRenamingDoesNot() {
    var trim = id("trim", "name", "Sport");
    var before = revision.current();

    assertThat(send(mvc.put().uri("/api/trims/{id}", trim), entry("Sport", 2, false)))
        .hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 1);
    assertThat(send(mvc.put().uri("/api/trims/{id}", trim), entry("Sport", 2, true))).hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 2);
    assertThat(send(mvc.put().uri("/api/trims/{id}", trim), entry("Sport Plus", 4, true)))
        .as("a new name and another place")
        .hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 2);

    assertThat(send(mvc.put().uri("/api/regions/EU"), entry("Europe", 3, false))).hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 3);
    assertThat(send(mvc.put().uri("/api/regions/EU"), entry("Europe", 3, true))).hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 4);
    assertThat(send(mvc.put().uri("/api/regions/EU"), entry("Europe and Africa", 1, true)))
        .as("a new name and another place")
        .hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 4);
  }

  @Test
  void addingChangingAndDeletingAGlobalRuleEachIncreaseItByOneAndARefusedChangeDoesNot() {
    var before = revision.current();
    var mats = id("feature", "code", "FLOOR_CARPET_MATS");
    var cover = id("feature", "code", "CARGO_COVER");
    var rails = id("feature", "code", "ROOF_RAILS");

    var added = send(mvc.post().uri("/api/global-rules"), requires(mats, cover));
    assertThat(added).hasStatus(201);
    assertThat(revision.current()).isEqualTo(before + 1);
    long rule = ApplicationIT.<Integer>read(added, "$[0].id");

    assertThat(send(mvc.post().uri("/api/global-rules"), requires(mats, cover)))
        .as("the same rule again")
        .hasStatus(422);
    assertThat(revision.current()).isEqualTo(before + 1);

    assertThat(send(mvc.put().uri("/api/global-rules/{id}", rule), requires(mats, rails)))
        .hasStatusOk();
    assertThat(revision.current()).isEqualTo(before + 2);
    assertThat(send(mvc.delete().uri("/api/global-rules/{id}", rule), null)).hasStatus(204);
    assertThat(revision.current()).isEqualTo(before + 3);
    assertThat(send(mvc.delete().uri("/api/global-rules/{id}", rule), null))
        .as("deleting what is gone")
        .hasStatus(404);
    assertThat(revision.current()).isEqualTo(before + 3);
  }

  private static String entry(String name, int sortOrder, boolean active) {
    return "{\"name\": \"%s\", \"sortOrder\": %d, \"active\": %s}"
        .formatted(name, sortOrder, active);
  }

  private static String requires(long source, long target) {
    return """
        {"kind": "REQUIRES", "sourceFeatureId": %d, "targetFeatureIds": [%d], "allRegions": true,
         "regionCodes": []}
        """
        .formatted(source, target);
  }

  private MvcTestResult send(MockMvcRequestBuilder request, String content) {
    request.with(signedInAs(Role.ADMIN)).with(csrfToken());
    if (content != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(content);
    }
    return request.exchange();
  }

  private long id(String table, String column, String value) {
    return jdbc.sql("SELECT id FROM %s WHERE %s = :value".formatted(table, column))
        .param("value", value)
        .query(Long.class)
        .single();
  }
}
