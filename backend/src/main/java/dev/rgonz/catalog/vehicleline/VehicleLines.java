package dev.rgonz.catalog.vehicleline;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.reference.ReferenceData;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library's vehicle lines. A line is added, renamed, retyped, activated, and deactivated, but
 * never deleted.
 */
@Service
class VehicleLines {
  private final VehicleLineRepository repository;
  private final ReferenceData reference;

  VehicleLines(VehicleLineRepository repository, ReferenceData reference) {
    this.repository = repository;
    this.reference = reference;
  }

  @Transactional(readOnly = true)
  List<VehicleLine> list() {
    return repository.findAllByOrderByName();
  }

  @Transactional
  VehicleLine add(String code, String name, String vehicleTypeCode) {
    requireVehicleType(vehicleTypeCode);

    return save(new VehicleLine(code, name.strip(), vehicleTypeCode));
  }

  @Transactional
  VehicleLine change(long id, String name, String vehicleTypeCode, boolean active) {
    requireVehicleType(vehicleTypeCode);
    var line = repository.findById(id).orElseThrow(ApiException::notFound);
    line.change(name.strip(), vehicleTypeCode, active);

    return save(line);
  }

  private void requireVehicleType(String code) {
    if (!reference.hasVehicleType(code)) {
      throw ApiException.invalid("vehicleTypeCode must be one of the vehicle types");
    }
  }

  /** The database keeps codes and names unique, which also settles two admins racing. */
  private VehicleLine save(VehicleLine line) {
    try {
      return repository.saveAndFlush(line);
    } catch (DataIntegrityViolationException taken) {
      throw ApiException.conflict(
          "NAME_TAKEN", "Another vehicle line already uses this code or name.");
    }
  }
}
