package dev.rgonz.catalog.reference;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the fixed lists: vehicle types, categories, and the configured model years. */
@Repository
public class FixedLists {
  private final JdbcClient jdbc;
  private final List<Integer> modelYears;

  FixedLists(JdbcClient jdbc, @Value("${app.model-years}") List<Integer> modelYears) {
    this.jdbc = jdbc;
    this.modelYears = modelYears;
  }

  /** The model years a catalog can be made for. */
  public List<Integer> modelYears() {
    return modelYears;
  }

  public boolean hasModelYear(int modelYear) {
    return modelYears.contains(modelYear);
  }

  List<Named> vehicleTypes() {
    return jdbc.sql("SELECT code, name FROM vehicle_type ORDER BY name").query(Named.class).list();
  }

  /** The categories in display order. */
  List<Named> categories() {
    return jdbc.sql("SELECT code, name FROM category ORDER BY sort_order")
        .query(Named.class)
        .list();
  }

  public boolean hasVehicleType(String code) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM vehicle_type WHERE code = :code)")
        .param("code", code)
        .query(Boolean.class)
        .single();
  }

  public boolean hasCategory(String code) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM category WHERE code = :code)")
        .param("code", code)
        .query(Boolean.class)
        .single();
  }

  /** An entry of a fixed list: its code and the name shown for it. */
  record Named(String code, String name) {}
}
