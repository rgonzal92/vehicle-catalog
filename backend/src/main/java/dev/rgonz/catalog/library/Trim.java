package dev.rgonz.catalog.library;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** An equipment level defined once in the library, such as Base or Sport. */
@Entity
class Trim implements Positioned {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;
  private int sortOrder;
  private boolean active = true;

  protected Trim() {}

  Trim(String name) {
    this.name = name;
  }

  /** Renames, activates, or deactivates the trim. Its place in the list is set separately. */
  void change(String name, boolean active) {
    this.name = name;
    this.active = active;
  }

  @Override
  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  Long getId() {
    return id;
  }

  String getName() {
    return name;
  }

  int getSortOrder() {
    return sortOrder;
  }

  boolean isActive() {
    return active;
  }
}
