package dev.rgonz.catalog.library;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores regions. */
interface RegionRepository extends JpaRepository<Region, String> {
  List<Region> findAllByOrderBySortOrder();
}
