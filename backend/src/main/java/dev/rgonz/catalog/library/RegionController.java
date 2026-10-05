package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.library.Regions.NewRegion;
import dev.rgonz.catalog.library.Regions.RegionChange;
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

/** Lists regions for everyone with a role and lets admins maintain them. */
@RestController
@RequestMapping("/api/regions")
class RegionController {
  private final Regions regions;

  RegionController(Regions regions) {
    this.regions = regions;
  }

  @GetMapping
  List<RegionView> list() {
    return regions.list().stream().map(RegionView::of).toList();
  }

  @PostMapping
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  RegionView add(@Valid @RequestBody NewRegion given) {
    return RegionView.of(regions.add(given));
  }

  @PutMapping("/{code}")
  @RequiresRole(Role.ADMIN)
  RegionView change(@PathVariable String code, @Valid @RequestBody RegionChange given) {
    return RegionView.of(regions.change(code, given));
  }

  /** A region as the API shows it. */
  record RegionView(String code, String name, int sortOrder, boolean active) {
    static RegionView of(Region region) {
      return new RegionView(
          region.getCode(), region.getName(), region.getSortOrder(), region.isActive());
    }
  }
}
