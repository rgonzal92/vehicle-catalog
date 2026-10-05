package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.ApiException;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The library's trims, in the order an admin gives them. A trim is added, renamed, moved,
 * activated, and deactivated, but never deleted.
 */
@Service
class Trims {
  private final TrimRepository repository;

  Trims(TrimRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  List<Trim> list() {
    return repository.findAllByOrderBySortOrder();
  }

  /** A new trim goes to the end of the list. */
  @Transactional
  Trim add(NewTrim given) {
    var trims = repository.findAllByOrderBySortOrder();
    var trim = new Trim(given.name().strip());
    Positions.move(trims, trim, trims.size() + 1);

    return save(trim);
  }

  @Transactional
  Trim change(long id, TrimChange given) {
    var trims = repository.findAllByOrderBySortOrder();
    var trim =
        trims.stream()
            .filter(candidate -> candidate.getId() == id)
            .findFirst()
            .orElseThrow(ApiException::notFound);
    trim.change(given.name().strip(), given.active());
    Positions.move(trims, trim, given.sortOrder());

    return save(trim);
  }

  /** The database keeps names unique whatever their case, which also settles two admins racing. */
  private Trim save(Trim trim) {
    try {
      return repository.saveAndFlush(trim);
    } catch (DataIntegrityViolationException taken) {
      throw ApiException.conflict("NAME_TAKEN", "Another trim already uses this name.");
    }
  }

  /** What an admin gives to add a trim. */
  record NewTrim(
      @NotBlank(message = "Enter a name.")
          @Size(max = 40, message = "Keep the name to 40 characters or fewer.")
          String name) {}

  /** What an admin gives to rename, move, activate, or deactivate a trim. */
  record TrimChange(
      @NotBlank(message = "Enter a name.")
          @Size(max = 40, message = "Keep the name to 40 characters or fewer.")
          String name,
      @NotNull(message = "Give the trim's place in the list.")
          @Min(value = 1, message = "The first place in the list is 1.")
          Integer sortOrder,
      @NotNull(message = "Say whether the trim is active.") Boolean active) {}
}
