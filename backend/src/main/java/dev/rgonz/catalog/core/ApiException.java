package dev.rgonz.catalog.core;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A refusal the caller can act on: a status, a stable code, and the reason in words. */
public final class ApiException extends ErrorResponseException {
  private ApiException(HttpStatusCode status, String code, String reason) {
    super(status, problem(status, code, reason), null);
  }

  /** Nothing exists at the address, or the caller may not know that it does. */
  public static ApiException notFound() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "There is nothing at this address.");
  }

  /** The caller may know the thing is there but may not do this to it. */
  public static ApiException forbidden(String code, String reason) {
    return new ApiException(HttpStatus.FORBIDDEN, code, reason);
  }

  /** Something the app relies on did not answer, so the request was not carried out. */
  public static ApiException unavailable(String code, String reason) {
    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, reason);
  }

  /** The request cannot be applied to the current state. */
  public static ApiException conflict(String code, String reason) {
    return new ApiException(HttpStatus.CONFLICT, code, reason);
  }

  /**
   * The request cannot be applied because other things depend on what it would take away. The
   * refusal lists them.
   */
  public static ApiException inUse(String reason, java.util.List<String> by) {
    var refusal = new ApiException(HttpStatus.CONFLICT, "IN_USE", reason);
    refusal.getBody().setProperty("usedBy", by);
    return refusal;
  }

  /**
   * The request cannot be applied while the catalog it is about has Errors. The refusal lists the
   * catalog's issues.
   */
  public static ApiException hasErrors(String reason, java.util.List<?> issues) {
    var refusal = new ApiException(HttpStatusCode.valueOf(422), "HAS_ERRORS", reason);
    refusal.getBody().setProperty("issues", issues);
    return refusal;
  }

  /**
   * An update of a catalog cannot be applied while conflicts of it are not settled. The refusal
   * lists them.
   */
  public static ApiException unresolvedConflicts(String reason, java.util.List<?> conflicts) {
    var refusal = new ApiException(HttpStatusCode.valueOf(422), "UNRESOLVED_CONFLICTS", reason);
    refusal.getBody().setProperty("conflicts", conflicts);
    return refusal;
  }

  /** The request is well formed but breaks a rule. */
  public static ApiException invalid(String reason) {
    return new ApiException(HttpStatusCode.valueOf(422), "VALIDATION", reason);
  }

  /** The request cannot be read as what the address takes. */
  public static ApiException badRequest(String reason) {
    return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", reason);
  }

  /** An edit of a catalog did not say which revision it was made from. */
  public static ApiException revisionRequired() {
    return new ApiException(
        HttpStatus.PRECONDITION_REQUIRED,
        "REVISION_REQUIRED",
        "Say which revision of the catalog this change was made from.");
  }

  /**
   * A revision conflict: the catalog changed after the writer last read it. The refusal names the
   * revision the catalog is at now.
   */
  public static ApiException revisionConflict(long currentRevision) {
    var refusal =
        new ApiException(
            HttpStatus.PRECONDITION_FAILED,
            "REVISION_CONFLICT",
            "This catalog was changed somewhere else after you opened it. Reload it to go on.");
    refusal.getBody().setProperty("revision", currentRevision);
    return refusal;
  }

  /** The request would take something past the most there can be of it. */
  public static ApiException limitExceeded(String reason) {
    return new ApiException(HttpStatusCode.valueOf(422), "LIMIT_EXCEEDED", reason);
  }

  private static ProblemDetail problem(HttpStatusCode status, String code, String reason) {
    var problem = ProblemDetail.forStatusAndDetail(status, reason);
    problem.setProperty("code", code);
    return problem;
  }
}
