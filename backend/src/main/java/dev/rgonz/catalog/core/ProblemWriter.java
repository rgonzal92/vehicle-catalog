package dev.rgonz.catalog.core;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes an API error as problem details with a code, for refusals made before a controller. */
@Component
class ProblemWriter {
  private final JsonMapper json;

  ProblemWriter(JsonMapper json) {
    this.json = json;
  }

  void write(HttpServletResponse response, HttpStatus status, String code) throws IOException {
    var problem = ProblemDetail.forStatus(status);
    problem.setProperty("code", code);

    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    json.writeValue(response.getOutputStream(), problem);
  }
}
