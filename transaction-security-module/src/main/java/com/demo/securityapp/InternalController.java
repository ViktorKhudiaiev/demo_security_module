package com.demo.securityapp;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.*;

@RestController
public class InternalController {
    private final Processor processor;
    private final AuditLog audit;
    public InternalController(Processor processor,AuditLog audit){this.processor=processor;this.audit=audit;}
    @GetMapping("/health") Map<String,Object> health(){
        boolean auditHealthy=audit.healthy(); Map<String,Object> queue=processor.deliveryHealth();
        return Map.of("service","integrity-processor","status",!"UP".equals(queue.get("status"))?"WAITING_FOR_QUEUE":auditHealthy?"UP":"WAITING_FOR_AUDIT","auditHealthy",auditHealthy,"queue",queue);
    }
    @PostMapping("/internal/accounts") ResponseEntity<Processor.Account> register(@RequestBody Register request){return ResponseEntity.status(201).body(processor.register(request.id(),request.name()));}
    @GetMapping("/internal/accounts/{id}") Processor.Account account(@PathVariable UUID id){return processor.account(id);}
    @PostMapping("/internal/accounts/{id}/hold") Processor.Account hold(@PathVariable UUID id,@RequestBody Hold request){if(request.held()==null)throw new IllegalArgumentException("held boolean is required");return processor.hold(id,request.held());}
    @GetMapping("/internal/operations/{id}") Map<String,Object> operation(@PathVariable UUID id){return processor.operation(id);}
    @GetMapping("/internal/audit") List<Map<String,Object>> audit(@RequestParam(required=false) UUID operationId){return audit.events(operationId);}
    @GetMapping("/internal/audit/proof/{index}") Map<String,Object> proof(@PathVariable int index){return audit.proof(index);}
    @PostMapping("/internal/test/faults") Map<String,Object> fault(@RequestBody Processor.Fault fault){processor.configureFault(fault);return Map.of("configured",true);}
    @GetMapping("/internal/test/faults/{id}") Map<String,Object> faultState(@PathVariable UUID id){return processor.faultState(id);}
    @PostMapping("/internal/test/checkpoint") Map<String,Object> checkpoint(){processor.faultState(UUID.randomUUID());audit.checkpoint();return Map.of("healthy",audit.healthy());}
    @ExceptionHandler(NoSuchElementException.class) ResponseEntity<Map<String,String>> missing(NoSuchElementException e){return ResponseEntity.status(404).body(Map.of("error",e.getMessage()));}
    @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<Map<String,String>> invalid(IllegalArgumentException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) ResponseEntity<Map<String,String>> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("error","Malformed request"));}
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,String>> failure(Exception e){return ResponseEntity.status(503).body(Map.of("error","Dependency unavailable; no unverified operation is authorized"));}
    public record Register(UUID id,String name){}
    public record Hold(Boolean held){}
}
