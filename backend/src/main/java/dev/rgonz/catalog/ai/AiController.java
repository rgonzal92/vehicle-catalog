package dev.rgonz.catalog.ai;

import dev.rgonz.catalog.user.AppUsers;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Says whether the model can be asked, so that what asks it can be shown as it is. */
@RestController
class AiController {
  private final Model model;
  private final AppUsers people;

  AiController(Model model, AppUsers people) {
    this.model = model;
    this.people = people;
  }

  /**
   * Whether the model can be asked by the caller now, and why not when it cannot. For anyone signed
   * in.
   */
  @GetMapping("/api/ai")
  Model.Availability availability(Authentication caller) {
    return model.availability(people.idOf(caller));
  }
}
