package dev.rgonz.catalog.vehicleline;

import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.vehicleline.VehicleLines.NewVehicleLine;
import dev.rgonz.catalog.vehicleline.VehicleLines.VehicleLineChange;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
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
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  VehicleLineView add(@Valid @RequestBody NewVehicleLine given) {
    return VehicleLineView.of(vehicleLines.add(given));
  }

  @PutMapping("/{id}")
  @RequiresRole(Role.ADMIN)
  VehicleLineView change(@PathVariable long id, @Valid @RequestBody VehicleLineChange given) {
    return VehicleLineView.of(vehicleLines.change(id, given));
  }

  /** A vehicle line as the API shows it. */
  record VehicleLineView(
      long id, String code, String name, String vehicleTypeCode, boolean active) {
    static VehicleLineView of(VehicleLine line) {
      return new VehicleLineView(
          line.getId(), line.getCode(), line.getName(), line.getVehicleTypeCode(), line.isActive());
    }
  }
}
