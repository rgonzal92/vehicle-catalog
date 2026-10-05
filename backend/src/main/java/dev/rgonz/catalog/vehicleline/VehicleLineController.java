package dev.rgonz.catalog.vehicleline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lists vehicle lines for everyone with a role and lets admins maintain them. */
@RestController
@RequestMapping("/api/vehicle-lines")
class VehicleLineController {
  private final VehicleLines vehicleLines;

  VehicleLineController(VehicleLines vehicleLines) {
    this.vehicleLines = vehicleLines;
  }

  @GetMapping
  List<VehicleLineView> list() {
    return vehicleLines.list().stream().map(VehicleLineView::of).toList();
  }

  @PostMapping
  @PreAuthorize("hasRole('ADMIN')")
  @ResponseStatus(HttpStatus.CREATED)
  VehicleLineView add(@Valid @RequestBody NewVehicleLine request) {
    return VehicleLineView.of(
        vehicleLines.add(request.code(), request.name(), request.vehicleTypeCode()));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  VehicleLineView change(@PathVariable long id, @Valid @RequestBody VehicleLineChange request) {
    return VehicleLineView.of(
        vehicleLines.change(id, request.name(), request.vehicleTypeCode(), request.active()));
  }

  /** A vehicle line as the API shows it. */
  record VehicleLineView(
      long id, String code, String name, String vehicleTypeCode, boolean active) {
    static VehicleLineView of(VehicleLine line) {
      return new VehicleLineView(
          line.getId(), line.getCode(), line.getName(), line.getVehicleTypeCode(), line.isActive());
    }
  }

  /** What an admin gives to add a vehicle line. Its code cannot change afterwards. */
  record NewVehicleLine(
      @NotNull
          @Pattern(
              regexp = "[A-Z][A-Z0-9_]{1,39}",
              message =
                  "must be 2 to 40 capital letters, digits, or underscores, starting with a"
                      + " letter")
          String code,
      @NotBlank @Size(max = 80) String name,
      @NotBlank String vehicleTypeCode) {}

  /** What an admin gives to rename, retype, activate, or deactivate a vehicle line. */
  record VehicleLineChange(
      @NotBlank @Size(max = 80) String name,
      @NotBlank String vehicleTypeCode,
      @NotNull Boolean active) {}
}
