package dev.rgonz.catalog.library;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** A market defined once in the library, such as Europe. Its code is its identity. */
@Entity
class Region implements Sortable, Persistable<String> {
  @Id private String code;

  private String name;
  private int sortOrder;
  private boolean active = true;

  /** A region made here is inserted, never merged over a stored one that has the same code. */
  @Transient private boolean stored;

  protected Region() {}

  Region(String code, String name) {
    this.code = code;
    this.name = name;
  }

  /** Renames, activates, or deactivates the region. Its place in the list is set separately. */
  void change(String name, boolean active) {
    this.name = name;
    this.active = active;
  }

  @Override
  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  @PostLoad
  @PostPersist
  void markStored() {
    stored = true;
  }

  @Override
  public String getId() {
    return code;
  }

  @Override
  public boolean isNew() {
    return !stored;
  }

  String getCode() {
    return code;
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
