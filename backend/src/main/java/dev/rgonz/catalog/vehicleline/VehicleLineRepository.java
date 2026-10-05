package dev.rgonz.catalog.vehicleline;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Stores vehicle lines. */
interface VehicleLineRepository extends JpaRepository<VehicleLine, Long> {
  List<VehicleLine> findAllByOrderByName();
}
