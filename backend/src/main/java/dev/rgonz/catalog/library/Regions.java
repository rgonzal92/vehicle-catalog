package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.ApiException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library's regions, in the order an admin gives them. A region is added, renamed, moved,
 * activated, and deactivated, but never deleted, and its code never changes.
 */
@Service
class Regions {
  private final RegionRepository repository;

  Regions(RegionRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  List<Region> list() {
    return repository.findAllByOrderBySortOrder();
  }

  /** A new region goes to the end of the list. */
  @Transactional
  Region add(NewRegion given) {
    var regions = repository.findAllByOrderBySortOrder();
    var region = new Region(given.code(), given.name().strip());
    Positions.move(regions, region, regions.size() + 1);

    return save(region);
  }

  @Transactional
  Region change(String code, RegionChange given) {
    var regions = repository.findAllByOrderBySortOrder();
    var region =
        regions.stream()
            .filter(candidate -> candidate.getCode().equals(code))
            .findFirst()
            .orElseThrow(ApiException::notFound);
    region.change(given.name().strip(), given.active());
    Positions.move(regions, region, given.sortOrder());

    return save(region);
  }

  /** The database keeps codes and names unique, which also settles two admins racing. */
  private Region save(Region region) {
    try {
      return repository.saveAndFlush(region);
    } catch (DataIntegrityViolationException taken) {
      throw ApiException.conflict("NAME_TAKEN", "Another region already uses this code or name.");
    }
  }

  /** What an admin gives to add a region. Its code cannot change afterwards. */
  record NewRegion(
      @NotNull(message = "Enter a code.")
          @Pattern(
              regexp = "[A-Z][A-Z0-9_]{1,19}",
              message =
                  "Use 2 to 20 capital letters, digits, or underscores for the code, starting"
                      + " with a letter.")
          String code,
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name) {}

  /** What an admin gives to rename, move, activate, or deactivate a region. */
  record RegionChange(
      @NotBlank(message = "Enter a name.")
          @Size(max = 80, message = "Keep the name to 80 characters or fewer.")
          String name,
      @NotNull(message = "Give the region's place in the list.")
          @Min(value = 1, message = "The first place in the list is 1.")
          Integer sortOrder,
      @NotNull(message = "Say whether the region is active.") Boolean active) {}
}
