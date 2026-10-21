package com.example.doris.gateway.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import com.example.doris.gateway.observability.Observation.*;
import java.util.*;

@RestController
public class ObservationController {
    private final CryptoObservations observations;private final AccountAccessService access;
    public ObservationController(CryptoObservations observations,AccountAccessService access){this.observations=observations;this.access=access;}
    @GetMapping("/api/observability/status") public Object status(){return observations.status();}
    @GetMapping("/api/observability/summary") public Object summary(@RequestParam(defaultValue="worker") String component,@RequestParam(defaultValue="ENCRYPT_QUERY") Operation operation,@RequestParam(defaultValue="15") int minutes){return observations.summary(component,operation,minutes);}
    @GetMapping("/api/observability/requests") public Object requests(@RequestParam(defaultValue="worker") String component,@RequestParam(defaultValue="ENCRYPT_QUERY") Operation operation,@RequestParam(required=false) Outcome outcome,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return observations.requests(component,operation,outcome,page,size);}
    @GetMapping("/api/observability/requests/{id}") public Object detail(@PathVariable String id){return observations.detail(id);}
    @GetMapping(value="/api/observability/metrics",produces="text/plain; version=0.0.4; charset=utf-8") public String metrics(){return observations.scrape();}
    @PostMapping("/api/internal/sql/observation") public Object receive(@RequestHeader("X-Gateway-Lineage-Token") String token,@RequestBody CryptoObservations.Event body){access.authenticate(token);observations.accept(body,true);return Collections.singletonMap("status","RECEIVED");}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid(){return ResponseEntity.badRequest().body(Collections.singletonMap("message","监控查询参数无效"));}
    @ExceptionHandler(SecurityException.class) public ResponseEntity<?> forbidden(){return ResponseEntity.status(403).body(Collections.singletonMap("message","内部监控认证失败"));}
}
