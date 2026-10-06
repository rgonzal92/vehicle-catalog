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

  /** The request cannot be applied to the current state. */
  public static ApiException conflict(String code, String reason) {
    return new ApiException(HttpStatus.CONFLICT, code, reason);
  }

  /** The request is well formed but breaks a rule. */
  public static ApiException invalid(String reason) {
    return new ApiException(HttpStatusCode.valueOf(422), "VALIDATION", reason);
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
