package com.example.doris.gateway.admin;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api")
public class InitializationController {
    private final InitializationService service;
    public InitializationController(InitializationService service){this.service=service;}
    @PostMapping("/initialization/preview") public Map<String,String> preview(@RequestBody Map<String,Object> r)throws Exception{return Collections.singletonMap("id",service.preview(r));}
    @GetMapping("/initialization/tasks") public List<Map<String,Object>> tasks(){return service.tasks();}
    @GetMapping("/initialization/tasks/{id}") public List<Map<String,Object>> items(@PathVariable String id){return service.items(id);}
    @PostMapping("/initialization/tasks/{id}/confirm") public Map<String,String> confirm(@PathVariable String id){service.confirm(id);return Collections.singletonMap("status","RUNNING");}
    @PostMapping("/managed-objects/approve-batch") public List<Map<String,Object>> approve(@RequestBody List<Long> ids){return service.approve(ids);}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST) public Map<String,String> bad(IllegalArgumentException e){return Collections.singletonMap("message",e.getMessage());}
}
