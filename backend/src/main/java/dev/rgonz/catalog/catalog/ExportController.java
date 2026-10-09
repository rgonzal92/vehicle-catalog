package dev.rgonz.catalog.catalog;

import dev.rgonz.catalog.catalog.Exports.Export;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.user.AppUsers;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets everyone who may open a catalog have it as a spreadsheet: they ask for an export, ask how it
 * stands, and are sent to its file once it is there.
 */
@RestController
class ExportController {
  private final Exports exports;
  private final AppUsers people;
  private final RoleHierarchy roles;

  ExportController(Exports exports, AppUsers people, RoleHierarchy roles) {
    this.exports = exports;
    this.people = people;
    this.roles = roles;
  }

  /**
   * Asks for the catalog as a spreadsheet, which the worker builds, and answers with its export.
   */
  @PostMapping("/api/catalogs/{id}/exports")
  ResponseEntity<Export> request(@PathVariable long id, Authentication caller) {
    var requester = people.idOf(caller);
    var export = exports.request(id, requester, reviews(caller));

    return ResponseEntity.accepted().body(exports.find(export, requester));
  }

  /** How an export the caller asked for stands. */
  @GetMapping("/api/exports/{id}")
  Export export(@PathVariable long id, Authentication caller) {
    return exports.find(id, people.idOf(caller));
  }

  /** Sends the caller to the file of an export they asked for, by a link that works for a while. */
  @GetMapping("/api/exports/{id}/download")
  ResponseEntity<Void> download(@PathVariable long id, Authentication caller) {
    return ResponseEntity.status(HttpStatus.SEE_OTHER)
        .location(exports.linkTo(id, people.idOf(caller), reviews(caller)))
        .build();
  }

  /** Whether the caller reviews catalogs, as a manager and an admin do. */
  private boolean reviews(Authentication caller) {
    return Role.heldBy(caller.getAuthorities(), roles).contains(Role.MANAGER);
  }
}
