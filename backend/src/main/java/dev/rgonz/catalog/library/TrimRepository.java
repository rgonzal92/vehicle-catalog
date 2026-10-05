package dev.rgonz.catalog.library;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores trims. */
interface TrimRepository extends JpaRepository<Trim, Long> {
  List<Trim> findAllByOrderBySortOrder();
}
