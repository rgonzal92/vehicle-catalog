package dev.rgonz.catalog.core;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** An endpoint only an admin may call, which proves that role checks on endpoints are enforced. */
@RestController
class AdminOnlyController {
  @GetMapping("/api/admin/check")
  @PreAuthorize("hasRole('ADMIN')")
  ResponseEntity<Void> check() {
    return ResponseEntity.ok().build();
  }
}
