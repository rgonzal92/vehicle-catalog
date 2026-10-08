package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.core.Seed;
import dev.rgonz.catalog.reference.FixedLists;
import dev.rgonz.catalog.user.DemoPeople;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes the largest catalog there can be, 500 feature rows by 96 offerings (12 trims sold in each
 * of 8 regions), for tests and for measuring how the app behaves at that size. It is an Approved
 * version of a vehicle line of its own, "Largest Catalog", made of library entries of its own, so
 * that a working copy of it is created and edited like any other.
 *
 * <p>It is made only where {@code app.largest-catalog} is true, which it is not unless it is set,
 * and nothing a visitor can reach makes it. There it is made at startup and made anew, with its
 * library entries, in each demo reset. A test makes it by calling {@link #create()}. It runs after
 * the other seeds, each of which fills only what is still empty. Where it is asked for, the author
 * and manager demo accounts have to say who they are, and the names it gives its library entries
 * have to be free, or the application does not start. Its 12 trims, 8 regions, and 500 features are
 * in the library from then on, where every catalog can add them.
 */
@Component
@Order(3)
class LargestCatalog implements Seed {
  private final JdbcClient jdbc;
  private final FixedLists fixedLists;
  private final DemoPeople demoPeople;
  private final boolean wanted;

  LargestCatalog(
      JdbcClient jdbc,
      FixedLists fixedLists,
      DemoPeople demoPeople,
      @Value("${app.largest-catalog:false}") boolean wanted) {
    this.jdbc = jdbc;
    this.fixedLists = fixedLists;
    this.demoPeople = demoPeople;
    this.wanted = wanted;
  }

  /** Runs once the application has started and in each demo reset, after the other seeds. */
  @Override
  @Transactional
  public void run(ApplicationArguments arguments) {
    if (wanted) {
      create();
    }
  }

  /**
   * Makes the largest catalog unless it is there already, and answers with the id of its Approved
   * version. It belongs to the demo author and is approved by the demo manager, as the seeded
   * catalogs are.
   */
  @Transactional
  long create() {
    var existing =
        jdbc.sql(
                """
                SELECT c.id
                FROM catalog c
                JOIN lineage l ON l.id = c.lineage_id
                JOIN vehicle_line v ON v.id = l.vehicle_line_id
                WHERE v.code = 'LARGEST_CATALOG' AND c.status = 'APPROVED'
                ORDER BY c.id
                LIMIT 1
                """)
            .query(Long.class)
            .optional();
    if (existing.isPresent()) {
      return existing.get();
    }

    var lineage =
        jdbc.sql(
                """
                WITH line AS (
                    INSERT INTO vehicle_line (code, name, vehicle_type_code)
                    VALUES ('LARGEST_CATALOG', 'Largest Catalog', 'CAR')
                    RETURNING id
                )
                INSERT INTO lineage (vehicle_line_id, model_year)
                SELECT id, :year FROM line
                RETURNING id
                """)
            .param("year", fixedLists.modelYears().getFirst())
            .query(Long.class)
            .single();
    var catalog =
        jdbc.sql(
                """
                INSERT INTO catalog
                    (lineage_id, name, owner_id, status, version_number, approved_by, approved_at)
                VALUES (:lineage, 'Largest catalog', :owner, 'APPROVED', 1, :approver, now())
                RETURNING id
                """)
            .param("lineage", lineage)
            .param("owner", demoPerson(Role.AUTHOR))
            .param("approver", demoPerson(Role.MANAGER))
            .query(Long.class)
            .single();
    jdbc.sql("UPDATE lineage SET current_catalog_id = :catalog WHERE id = :lineage")
        .param("catalog", catalog)
        .param("lineage", lineage)
        .update();

    // Each kind of library entry is made and added to the catalog in one statement, so the catalog
    // gets exactly the entries made for it. An Approved version carries the labels it was approved
    // with.
    jdbc.sql(
            """
            WITH made AS (
                INSERT INTO trim (name, sort_order)
                SELECT 'Largest ' || to_char(n, 'FM00'),
                       (SELECT COALESCE(max(sort_order), 0) FROM trim) + n
                FROM generate_series(1, 12) AS n
                RETURNING id, name, sort_order
            )
            INSERT INTO catalog_trim (catalog_id, trim_id, approved_name, approved_sort_order)
            SELECT :catalog, id, name, sort_order FROM made
            """)
        .param("catalog", catalog)
        .update();
    jdbc.sql(
            """
            WITH made AS (
                INSERT INTO region (code, name, sort_order)
                SELECT 'LARGEST' || n, 'Largest region ' || n,
                       (SELECT COALESCE(max(sort_order), 0) FROM region) + n
                FROM generate_series(1, 8) AS n
                RETURNING code, name
            )
            INSERT INTO catalog_region (catalog_id, region_code, approved_name)
            SELECT :catalog, code, name FROM made
            """)
        .param("catalog", catalog)
        .update();
    // The features are spread over every category but Packages, which only packages are in.
    jdbc.sql(
            """
            WITH made AS (
                INSERT INTO feature (code, name, category_code, kind)
                SELECT 'LARGEST_' || to_char(n, 'FM000'),
                       'Largest feature ' || to_char(n, 'FM000'),
                       categories.codes[1 + n % cardinality(categories.codes)], 'FEATURE'
                FROM generate_series(1, 500) AS n,
                     (SELECT array_agg(code ORDER BY sort_order) AS codes
                      FROM category WHERE code <> 'PACKAGES') AS categories
                RETURNING id, name, category_code
            )
            INSERT INTO catalog_feature
                (catalog_id, feature_id, approved_name, approved_category_code)
            SELECT :catalog, id, name, category_code FROM made
            """)
        .param("catalog", catalog)
        .update();
    jdbc.sql(
            """
            INSERT INTO catalog_trim_region (catalog_id, trim_id, region_code)
            SELECT :catalog, t.trim_id, r.region_code
            FROM catalog_trim t
            JOIN catalog_region r ON r.catalog_id = t.catalog_id
            WHERE t.catalog_id = :catalog
            """)
        .param("catalog", catalog)
        .update();
    // A fixed pattern, so every catalog made is the same: in each offering a quarter of the
    // features are Standard, a quarter Available, and the rest Not offered, which is no cell.
    jdbc.sql(
            """
            INSERT INTO catalog_cell (catalog_id, feature_id, trim_id, region_code, availability)
            SELECT :catalog, f.feature_id, o.trim_id, o.region_code,
                   CASE (f.place + o.place) % 4 WHEN 0 THEN 'S' ELSE 'A' END
            FROM (SELECT feature_id, row_number() OVER (ORDER BY feature_id) AS place
                  FROM catalog_feature WHERE catalog_id = :catalog) AS f
            CROSS JOIN (SELECT trim_id, region_code,
                               row_number() OVER (ORDER BY region_code, trim_id) AS place
                        FROM catalog_trim_region WHERE catalog_id = :catalog) AS o
            WHERE (f.place + o.place) % 4 < 2
            """)
        .param("catalog", catalog)
        .update();

    return catalog;
  }

  private long demoPerson(Role role) {
    return demoPeople
        .record(role)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "The largest catalog needs the %s demo account to say who it is, with a"
                            .formatted(role.key())
                        + " subject, a username, and a display name in app.demo-accounts"));
  }
}
