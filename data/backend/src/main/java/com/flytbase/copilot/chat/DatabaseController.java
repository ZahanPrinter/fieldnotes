package com.flytbase.copilot.chat;

import com.flytbase.copilot.core.DatasetRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/databases")
public class DatabaseController {
  public record EditRequest(Integer expectedVersion, JsonNode changes, String changedBy, String reason) {}
  private final DatasetRegistry registry;
  public DatabaseController(DatasetRegistry registry) { this.registry = registry; }
  @GetMapping public Map<String,Object> list() throws Exception { return Map.of("databases",registry.list(),"selected",registry.current().databaseId()); }
  @PostMapping(path="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public DatasetRegistry.DatabaseInfo upload(@RequestPart("file") MultipartFile file) {
    try { return registry.importZip(file.getOriginalFilename()==null?"Imported database.zip":file.getOriginalFilename(),file); }
    catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage()); }
    catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not import this dataset: "+e.getMessage()); }
  }
  @PostMapping("/{id}/select") public Map<String,Object> select(@PathVariable String id) {
    try { registry.select(id); return Map.of("selected",id,"database",registry.list().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow()); }
    catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage()); }
    catch (Exception e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not select database: "+e.getMessage()); }
  }
  @GetMapping("/history") public List<Map<String,Object>> history() throws Exception { return registry.history(); }
  @GetMapping("/{id}/types") public List<Map<String,Object>> types(@PathVariable String id) {
    try{return registry.entityTypes(id);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not read record types: "+e.getMessage());}
  }
  @GetMapping("/{id}/records") public Map<String,Object> records(@PathVariable String id,@RequestParam(defaultValue="inspection") String type,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset) {
    try{return registry.records(id,type,q,limit,offset);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not read records: "+e.getMessage());}
  }
  @GetMapping("/{id}/records/{type}/{recordId}") public Map<String,Object> record(@PathVariable String id,@PathVariable String type,@PathVariable String recordId) {
    try{return registry.record(id,type,recordId);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not read this record: "+e.getMessage());}
  }
  @PutMapping("/{id}/records/{type}/{recordId}") public Map<String,Object> edit(@PathVariable String id,@PathVariable String type,@PathVariable String recordId,@RequestBody EditRequest request) {
    if(request.expectedVersion()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Record version is required.");
    try{return registry.editRecord(id,type,recordId,request.expectedVersion(),request.changes(),request.changedBy(),request.reason());}
    catch(DatasetRegistry.RecordConflictException e){throw new ResponseStatusException(HttpStatus.CONFLICT,e.getMessage());}
    catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
    catch(Exception e){throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,"Could not save this change: "+e.getMessage());}
  }
}
