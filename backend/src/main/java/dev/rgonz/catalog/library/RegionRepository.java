package dev.rgonz.catalog.library;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores regions. */
interface RegionRepository extends JpaRepository<Region, String> {
  /** In sort order. Entries left with the same sort order by a race keep a steady order. */
  List<Region> findAllByOrderBySortOrderAscCodeAsc();
}
