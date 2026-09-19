package io.arcnode.mockderms.callapi;

import io.arcnode.mockderms.callapi.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes the outbound call over {@code GET /call-api}. */
@Tag(name = "call-api")
@RestController
@RequestMapping("/call-api")
public class CallApiController {

  private final CallApiService service;

  public CallApiController(CallApiService service) {
    this.service = service;
  }

  @GetMapping
  public ApiResponse call() {
    return service.fetchUrl();
  }
}
