package dev.rgonz.catalog.reference;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the fixed lists: vehicle types and categories. */
@Repository
public class ReferenceData {
  private final JdbcClient jdbc;

  ReferenceData(JdbcClient jdbc) {
    this.jdbc = jdbc;
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

  /** An entry of a fixed list: its code and the name shown for it. */
  record Named(String code, String name) {}
}
