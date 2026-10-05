package dev.rgonz.catalog.core;

import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Answers request failures with problem details that carry a code. A refusal that names its own
 * code keeps it; any other failure gets a code named after its status.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  /** A body that breaks a field's rule is refused with 422 and the rules it broke. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var broken =
        exception.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + " " + error.getDefaultMessage())
            .sorted()
            .collect(Collectors.joining("; "));
    var refusal = ApiException.invalid(broken);

    return createResponseEntity(refusal.getBody(), headers, refusal.getStatusCode(), request);
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    if (body instanceof ProblemDetail problem
        && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))
        && HttpStatus.resolve(status.value()) instanceof HttpStatus known) {
      problem.setProperty("code", known.name());
    }

    return super.createResponseEntity(body, headers, status, request);
  }
}
