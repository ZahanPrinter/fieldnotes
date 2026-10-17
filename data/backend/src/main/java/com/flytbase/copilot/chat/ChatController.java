package com.flytbase.copilot.chat;

import com.flytbase.copilot.core.*;
import com.flytbase.copilot.core.DatasetRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ChatController {
  public record ChatRequest(String userId, String conversationId, String message) {}
  public record ConversationSyncRequest(String userId, List<JsonNode> conversations) {}

  private final DatasetRegistry registry; private final MemoryStore memory;

  public ChatController(DatasetRegistry registry, MemoryStore memory) { this.registry = registry; this.memory = memory; }

  @GetMapping("/users")
  public Collection<Models.User> users() { return registry.current().dataset().users.values(); }

  @GetMapping("/conversations")
  public List<Map<String,Object>> conversations(@RequestParam String userId) throws Exception {
    requireUser(userId);return registry.conversations(registry.current().databaseId(),userId);
  }

  @PostMapping("/conversations/sync")
  public Map<String,Object> syncConversations(@RequestBody ConversationSyncRequest request) throws Exception {
    requireUser(request.userId());registry.syncConversations(registry.current().databaseId(),request.userId(),request.conversations());
    return Map.of("conversations",registry.conversations(registry.current().databaseId(),request.userId()));
  }

  @DeleteMapping("/conversations/{conversationId}")
  public Map<String,Object> deleteConversation(@PathVariable String conversationId,@RequestParam String userId) throws Exception {
    requireUser(userId);
    try{String memoryScope=userId+"@"+registry.current().databaseId();registry.deleteConversation(registry.current().databaseId(),userId,conversationId);memory.deleteConversation(memoryScope,conversationId);return Map.of("deleted",conversationId);}
    catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,e.getMessage());}
  }

  @PostMapping("/chat")
  public Map<String, Object> chat(@RequestBody ChatRequest r) throws Exception {
    DatasetRegistry.Runtime runtime = registry.current();
    if (r.userId() == null || !runtime.dataset().users.containsKey(r.userId())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user");
    if (r.message() == null || r.message().isBlank() || r.message().length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must be 1-2000 characters");
    String conv = r.conversationId() == null || r.conversationId().isBlank() ? UUID.randomUUID().toString() : r.conversationId();
    String memoryScope = r.userId() + "@" + runtime.databaseId();
    Agent.Answer a;
    try {
      a = runtime.agent().run(r.userId(), memory.memoryBlock(memoryScope, conv), registry.conversationHistory(runtime.databaseId(),r.userId(),conv), r.message());
    } catch (java.io.IOException | InterruptedException e) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The local model (Ollama) is not reachable: " + e.getMessage());
    }
    registry.saveChatTurn(runtime.databaseId(),r.userId(),conv,r.message(),a);
    memory.append(memoryScope, conv, r.message(), a.text(), a.wells(), runtime.tools().asOf);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("conversationId", conv); out.put("answer", a.text()); out.put("citations", a.citations());
    out.put("visual", a.visual()); out.put("grounded", a.grounded()); out.put("trace", a.trace());
    return out;
  }

  private void requireUser(String userId){
    if(userId==null||!registry.current().dataset().users.containsKey(userId))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Unknown user");
  }
}
