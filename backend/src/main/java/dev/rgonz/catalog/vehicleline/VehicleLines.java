package dev.rgonz.catalog.vehicleline;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.reference.FixedLists;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
  private final FixedLists fixedLists;

  VehicleLines(VehicleLineRepository repository, FixedLists fixedLists) {
    this.repository = repository;
    this.fixedLists = fixedLists;
  }

  @Transactional(readOnly = true)
  List<VehicleLine> list() {
    return repository.findAllByOrderByName();
  }

  @Transactional
  VehicleLine add(NewVehicleLine given) {
    requireVehicleType(given.vehicleTypeCode());

    return save(new VehicleLine(given.code(), given.name().strip(), given.vehicleTypeCode()));
  }

  @Transactional
  VehicleLine change(long id, VehicleLineChange given) {
    requireVehicleType(given.vehicleTypeCode());
    var line = repository.findById(id).orElseThrow(ApiException::notFound);
    line.change(given.name().strip(), given.vehicleTypeCode(), given.active());

    return save(line);
  }

  private void requireVehicleType(String code) {
    if (!fixedLists.hasVehicleType(code)) {
      throw ApiException.invalid("Choose one of the vehicle types.");
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

  /** What an admin gives to add a vehicle line. Its code cannot change afterwards. */
  record NewVehicleLine(
      @NotNull(message = "Enter a code.")
          @Pattern(
              regexp = "[A-Z][A-Z0-9_]{1,39}",
              message =
                  "Use 2 to 40 capital letters, digits, or underscores for the code, starting"
                      + " with a letter.")
          String code,
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name,
      @NotBlank(message = "Choose a vehicle type.") String vehicleTypeCode) {}

  /** What an admin gives to rename, retype, activate, or deactivate a vehicle line. */
  record VehicleLineChange(
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name,
      @NotBlank(message = "Choose a vehicle type.") String vehicleTypeCode,
      @NotNull(message = "Say whether the vehicle line is active.") Boolean active) {}
}
