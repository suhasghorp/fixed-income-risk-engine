package com.fixedincomerisk.stream;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * The risk stream (Server-Sent Events): a Risk Snapshot on connect, then a Risk Update per cycle. A
 * client that detects a sequence gap requests a fresh Risk Snapshot by reconnecting.
 */
@RestController
class RiskStreamController {

    private final LiveRiskStream stream;

    RiskStreamController(LiveRiskStream stream) {
        this.stream = stream;
    }

    @GetMapping(path = "/api/risk/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> stream() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .body(stream.subscribe());
    }
}
