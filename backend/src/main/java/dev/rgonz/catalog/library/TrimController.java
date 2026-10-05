package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.library.Trims.NewTrim;
import dev.rgonz.catalog.library.Trims.TrimChange;
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

/** Lists trims for everyone with a role and lets admins maintain them. */
@RestController
@RequestMapping("/api/trims")
class TrimController {
  private final Trims trims;

  TrimController(Trims trims) {
    this.trims = trims;
  }

  @GetMapping
  List<TrimView> list() {
    return trims.list().stream().map(TrimView::of).toList();
  }

  @PostMapping
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  TrimView add(@Valid @RequestBody NewTrim given) {
    return TrimView.of(trims.add(given));
  }

  @PutMapping("/{id}")
  @RequiresRole(Role.ADMIN)
  TrimView change(@PathVariable long id, @Valid @RequestBody TrimChange given) {
    return TrimView.of(trims.change(id, given));
  }

  /** A trim as the API shows it. */
  record TrimView(long id, String name, int sortOrder, boolean active) {
    static TrimView of(Trim trim) {
      return new TrimView(trim.getId(), trim.getName(), trim.getSortOrder(), trim.isActive());
    }
  }
}
