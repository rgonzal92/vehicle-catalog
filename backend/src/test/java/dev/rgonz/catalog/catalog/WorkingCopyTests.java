package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.ApplicationIT;
import dev.rgonz.catalog.core.Role;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What the tests of working copies share: people on record, and ways to create, open, and edit one.
 */
abstract class WorkingCopyTests extends ApplicationIT {
  @Autowired protected MeterRegistry meters;

  /** How often something has been counted so far, told apart by the tags as keys and values. */
  protected double counted(String counter, String... tags) {
    return meters.get(counter).tags(tags).counter().count();
  }

  /** Ana, an author who is on record. */
  protected RequestPostProcessor ana() {
    return signedInAs(Role.AUTHOR, "ana");
  }

  /** Ben, another author who is on record. */
  protected RequestPostProcessor ben() {
    return signedInAs(Role.AUTHOR, "ben");
  }

  /**
   * Makes Ana the owner of every Approved version. They count neither towards the names her working
   * copies may have nor towards how many she may own.
   */
  protected void anaOwnsTheApprovedVersions() {
    jdbc.sql("UPDATE catalog SET owner_id = :ana WHERE status = 'APPROVED'")
        .param("ana", person("ana"))
        .update();
  }

  protected MvcTestResult create(
      RequestPostProcessor who, String name, long vehicleLineId, int modelYear) {
    return mvc.post()
        .uri("/api/catalogs")
        .with(who)
        .with(csrfToken())
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            """
            {"name": "%s", "vehicleLineId": %d, "modelYear": %d}
            """
                .formatted(name, vehicleLineId, modelYear))
        .exchange();
  }

  /** Creates a working copy for the vehicle line's model year and answers with its id. */
  protected long workingCopy(RequestPostProcessor who, String vehicleLine, int modelYear) {
    return idOf(create(who, vehicleLine + " " + modelYear, line(vehicleLine), modelYear));
  }

  protected MvcTestResult open(RequestPostProcessor who, long catalog) {
    return mvc.get().uri("/api/catalogs/" + catalog).with(who).exchange();
  }

  /** Sends the cells as one save that names the revision, or none when it is null. */
  protected MvcTestResult setCells(
      RequestPostProcessor who, long catalog, String revision, String... cells) {
    var request =
        mvc.put()
            .uri("/api/catalogs/{id}/cells", catalog)
            .with(who)
            .with(csrfToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content(Stream.of(cells).collect(Collectors.joining(",", "[", "]")));

    return (revision == null ? request : request.header("If-Match", revision)).exchange();
  }

  /** A cell of a save, with its feature named by code and its trim by name. */
  protected String cell(String feature, String trim, String region, String availability) {
    return """
        {"featureId": %d, "trimId": %d, "regionCode": "%s", "availability": "%s"}
        """
        .formatted(feature(feature), trim(trim), region, availability);
  }

  /**
   * Sends an edit that names the revision, or none when it is null, with a body when it has one.
   */
  protected MvcTestResult edit(
      RequestPostProcessor who, MockMvcRequestBuilder request, String revision, String body) {
    request.with(who).with(csrfToken());
    if (revision != null) {
      request.header("If-Match", revision);
    }
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    return request.exchange();
  }

  protected static long idOf(MvcTestResult created) {
    return ApplicationIT.<Integer>read(created, "$.id");
  }

  protected long line(String code) {
    return id("vehicle_line", "code", code);
  }

  protected long trim(String name) {
    return id("trim", "name", name);
  }

  protected long feature(String code) {
    return id("feature", "code", code);
  }

  /** The catalog that is the given Approved version of the vehicle line's model year. */
  protected long approved(String vehicleLine, int modelYear, int version) {
    return jdbc.sql(
            """
            SELECT c.id
            FROM catalog c
            JOIN lineage l ON l.id = c.lineage_id
            WHERE l.vehicle_line_id = :line AND l.model_year = :year AND c.version_number = :version
            """)
        .param("line", line(vehicleLine))
        .param("year", modelYear)
        .param("version", version)
        .query(Long.class)
        .single();
  }

  protected long revision(long catalog) {
    return jdbc.sql("SELECT revision FROM catalog WHERE id = :id")
        .param("id", catalog)
        .query(Long.class)
        .single();
  }

  protected long count(String from, Object... values) {
    return jdbc.sql("SELECT count(*) FROM " + from.formatted(values)).query(Long.class).single();
  }

  private long id(String table, String column, String value) {
    return jdbc.sql("SELECT id FROM %s WHERE %s = :value".formatted(table, column))
        .param("value", value)
        .query(Long.class)
        .single();
  }
}
