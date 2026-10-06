package dev.rgonz.catalog.vehicleline;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A product sold across model years, such as "Compact SUV". It has one vehicle type. */
@Entity
public class VehicleLine {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String code;
  private String name;
  private String vehicleTypeCode;
  private boolean active = true;

  protected VehicleLine() {}

  VehicleLine(String code, String name, String vehicleTypeCode) {
    this.code = code;
    this.name = name;
    this.vehicleTypeCode = vehicleTypeCode;
  }

  /** Renames, retypes, activates, or deactivates the line. Its code never changes. */
  void change(String name, String vehicleTypeCode, boolean active) {
    this.name = name;
    this.vehicleTypeCode = vehicleTypeCode;
    this.active = active;
  }

  Long getId() {
    return id;
  }

  String getCode() {
    return code;
  }

  String getName() {
    return name;
  }

  String getVehicleTypeCode() {
    return vehicleTypeCode;
  }

  boolean isActive() {
    return active;
  }
}
