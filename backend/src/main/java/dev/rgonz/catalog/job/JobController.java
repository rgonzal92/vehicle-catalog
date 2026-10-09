package dev.rgonz.catalog.job;

import dev.rgonz.catalog.core.ApiException;
import dev.rgonz.catalog.core.RequiresRole;
import dev.rgonz.catalog.core.Role;
import dev.rgonz.catalog.job.Jobs.JobPage;
import dev.rgonz.catalog.job.Jobs.Listed;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shows admins the jobs, with how each one stands, and lets them have a failed one retried. */
@RestController
class JobController {
  private static final Set<String> STATUSES = Set.of("QUEUED", "SUCCEEDED", "FAILED");

  private final Jobs jobs;

  JobController(Jobs jobs) {
    this.jobs = jobs;
  }

  /** The jobs, newest first and a page at a time, of one status if one is named. */
  @GetMapping("/api/jobs")
  @RequiresRole(Role.ADMIN)
  JobPage list(
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size) {
    if (status != null && !STATUSES.contains(status)) {
      throw ApiException.badRequest("A job is QUEUED, SUCCEEDED, or FAILED.");
    }
    return jobs.page(status, page, size);
  }

  /** Has a failed job tried once more, and answers with the job as it then stands. */
  @PostMapping("/api/jobs/{id}/retry")
  @RequiresRole(Role.ADMIN)
  Listed retry(@PathVariable long id) {
    return jobs.retry(id);
  }
}
