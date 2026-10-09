package dev.rgonz.catalog.library;

import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.library.GlobalRules.GlobalRule;
import dev.rgonz.catalog.library.GlobalRules.RuleContent;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Lists the global rules for everyone with a role and lets admins maintain them. */
@RestController
@RequestMapping("/api/global-rules")
class GlobalRuleController {
  private final GlobalRules rules;

  GlobalRuleController(GlobalRules rules) {
    this.rules = rules;
  }

  @GetMapping
  List<GlobalRule> list() {
    return rules.list();
  }

  /** Adds a rule, or for an exclusion a pair for each target, and answers with what was made. */
  @PostMapping
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.CREATED)
  List<GlobalRule> add(@Valid @RequestBody RuleContent given) {
    return rules.add(given);
  }

  @PutMapping("/{id}")
  @RequiresRole(Role.ADMIN)
  GlobalRule change(@PathVariable long id, @Valid @RequestBody RuleContent given) {
    return rules.change(id, given);
  }

  @DeleteMapping("/{id}")
  @RequiresRole(Role.ADMIN)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable long id) {
    rules.delete(id);
  }
}
