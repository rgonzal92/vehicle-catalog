package dev.rgonz.catalog.library;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;

/**
 * Anything a vehicle can be equipped with, defined once in the library. Its code and kind never
 * change.
 */
@Entity
class Feature {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String code;
  private String name;
  private String description;
  private String categoryCode;

  @Enumerated(EnumType.STRING)
  private Kind kind;

  @Enumerated(EnumType.STRING)
  private Status status = Status.ACTIVE;

  /**
   * Counts the changes made to the feature, so a change made from an earlier version is refused.
   */
  @Version private long version;

  protected Feature() {}

  Feature(String code, String name, String description, String categoryCode, Kind kind) {
    this.code = code;
    this.name = name;
    this.description = description;
    this.categoryCode = categoryCode;
    this.kind = kind;
  }

  /** Renames, describes, or recategorizes the feature. */
  void change(String name, String description, String categoryCode) {
    this.name = name;
    this.description = description;
    this.categoryCode = categoryCode;
  }

  void setStatus(Status status) {
    this.status = status;
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

  String getDescription() {
    return description;
  }

  String getCategoryCode() {
    return categoryCode;
  }

  Kind getKind() {
    return kind;
  }

  Status getStatus() {
    return status;
  }

  long getVersion() {
    return version;
  }

  /** Whether the feature stands alone or is a package, which brings other features with it. */
  enum Kind {
    FEATURE,
    PACKAGE
  }

  /** A retired feature can no longer be added to catalogs or rules. */
  enum Status {
    ACTIVE,
    RETIRED
  }
}
