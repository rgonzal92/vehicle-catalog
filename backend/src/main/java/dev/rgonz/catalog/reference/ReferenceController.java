package dev.rgonz.catalog.reference;

import dev.rgonz.catalog.reference.FixedLists.Named;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves the fixed lists the rest of the app is built on. */
@RestController
class ReferenceController {
  private final FixedLists fixedLists;

  ReferenceController(FixedLists fixedLists) {
    this.fixedLists = fixedLists;
  }

  @GetMapping("/api/reference")
  Reference reference() {
    return new Reference(
        fixedLists.vehicleTypes(), fixedLists.categories(), fixedLists.modelYears());
  }

  /** The vehicle types, the categories in display order, and the model years catalogs can use. */
  record Reference(List<Named> vehicleTypes, List<Named> categories, List<Integer> modelYears) {}
}
