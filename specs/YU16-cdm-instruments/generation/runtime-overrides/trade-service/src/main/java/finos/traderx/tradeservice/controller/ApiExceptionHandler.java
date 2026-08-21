package finos.traderx.tradeservice.controller;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * YU16: validation failures reach the client as {@code {"detail": "<message>"}} with the exact
 * message the validator produced (FR-CDM16/27) — the UI surfaces {@code detail} verbatim in the
 * ticket instead of a generic failure.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> handle(ResponseStatusException ex) {
    String detail = ex.getReason() == null ? ex.getMessage() : ex.getReason();
    return ResponseEntity.status(ex.getStatusCode()).body(Map.of("detail", detail));
  }
}
