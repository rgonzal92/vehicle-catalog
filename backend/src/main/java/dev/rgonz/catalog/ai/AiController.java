package dev.rgonz.catalog.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Says whether the model can be asked, so that what asks it can be shown as it is. */
@RestController
class AiController {
  private final Model model;

  AiController(Model model) {
    this.model = model;
  }

  /** Whether the model can be asked now, and why not when it cannot. For anyone signed in. */
  @GetMapping("/api/ai")
  Model.Availability availability() {
    return model.availability();
  }
}
