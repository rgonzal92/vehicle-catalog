package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.library.Feature.Kind;
import dev.rgonz.catalog.library.Feature.Status;
import dev.rgonz.catalog.library.Features.FeatureChange;
import dev.rgonz.catalog.library.Features.FeatureSearch;
import dev.rgonz.catalog.library.Features.NewFeature;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Searches the feature library for everyone with a role and lets admins maintain it. */
@RestController
@RequestMapping("/api/features")
class FeatureController {
  private final Features features;

  FeatureController(Features features) {
    this.features = features;
  }

  /** Pages are numbered from 0. */
  @GetMapping
  FeaturePage search(
      @RequestParam(defaultValue = "") String query,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Kind kind,
      @RequestParam(required = false) Status status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
    var found = features.search(new FeatureSearch(query, category, kind, status), page, size);

    return new FeaturePage(
        found.getContent().stream().map(FeatureView::of).toList(), found.getTotalElements());
  }

  @PostMapping
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  FeatureView add(@Valid @RequestBody NewFeature given) {
    return FeatureView.of(features.add(given));
  }

  @PutMapping("/{id}")
  @RequiresRole(Role.ADMIN)
  FeatureView change(@PathVariable long id, @Valid @RequestBody FeatureChange given) {
    return FeatureView.of(features.change(id, given));
  }

  @PostMapping("/{id}/retire")
  @RequiresRole(Role.ADMIN)
  FeatureView retire(@PathVariable long id) {
    return FeatureView.of(features.setStatus(id, Status.RETIRED));
  }

  @PostMapping("/{id}/reactivate")
  @RequiresRole(Role.ADMIN)
  FeatureView reactivate(@PathVariable long id) {
    return FeatureView.of(features.setStatus(id, Status.ACTIVE));
  }

  /** One page of a search, with how many features the whole search found. */
  record FeaturePage(List<FeatureView> items, long total) {}

  /** A feature as the API shows it. */
  record FeatureView(
      long id,
      String code,
      String name,
      String description,
      String categoryCode,
      Kind kind,
      Status status,
      long version) {
    static FeatureView of(Feature feature) {
      return new FeatureView(
          feature.getId(),
          feature.getCode(),
          feature.getName(),
          feature.getDescription(),
          feature.getCategoryCode(),
          feature.getKind(),
          feature.getStatus(),
          feature.getVersion());
    }
  }
}
