package io.arcnode.mockderms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Root routes. {@code GET /} is the liveness probe; {@code /actuator/health} is the real one. */
@Tag(name = "app")
@RestController
public class AppController {

  @Operation(summary = "Health check")
  @GetMapping("/")
  public String healthCheck() {
    return "ok";
  }
}
