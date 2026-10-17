package com.flytbase.copilot.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.flytbase.copilot.core.Dataset;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Private per-user memory, keyed strictly by user id. Nothing here is ever read for another user.
 * (Shared knowledge lives in the Overlay, which is global by design.) In-memory for now; swap for a
 * table with row-level security for persistence.
 */
public final class MemoryStore {
  private static final int MAX_HISTORY = 14, MAX_TOPICS = 12, MAX_CONVERSATIONS_PER_USER = 100;
  private final Map<String, LinkedHashMap<String, List<JsonNode>>> conversations = new ConcurrentHashMap<>();
  private record Topic(String conversationId,String text) {}
  private final Map<String, Deque<Topic>> topics = new ConcurrentHashMap<>();

  public List<JsonNode> history(String userId, String convId) {
    LinkedHashMap<String, List<JsonNode>> byConversation = conversations.get(userId);
    if (byConversation == null) return List.of();
    synchronized (byConversation) {
      List<JsonNode> list = byConversation.get(convId);
      return list == null ? List.of() : List.copyOf(list);
    }
  }

  public void append(String userId, String convId, String question, String answer, List<String> wells, LocalDate today) {
    LinkedHashMap<String, List<JsonNode>> byConversation = conversations.computeIfAbsent(userId, ignored -> new LinkedHashMap<>(16, .75f, true));
    synchronized (byConversation) {
      List<JsonNode> list = byConversation.computeIfAbsent(convId, ignored -> new ArrayList<>());
      list.add(Dataset.M.createObjectNode().put("role", "user").put("content", question));
      String tag = wells.isEmpty() ? "" : "\n[wells in this answer: " + String.join(", ", wells) + "]";
      list.add(Dataset.M.createObjectNode().put("role", "assistant").put("content", answer + tag));
      if (list.size() > MAX_HISTORY) list.subList(0, list.size() - MAX_HISTORY).clear();
      while (byConversation.size() > MAX_CONVERSATIONS_PER_USER) {
        String oldest = byConversation.keySet().iterator().next();
        byConversation.remove(oldest);
      }
    }
    var t = topics.computeIfAbsent(userId, k -> new ArrayDeque<>());
    synchronized (t) {
      t.addLast(new Topic(convId,today + " | asked: \"" + (question.length() > 160 ? question.substring(0, 160) + "..." : question) + "\"" + (wells.isEmpty() ? "" : " | wells: " + String.join(", ", wells))));
      while (t.size() > MAX_TOPICS) t.removeFirst();
    }
  }

  public void deleteConversation(String userId,String convId) {
    LinkedHashMap<String,List<JsonNode>> byConversation=conversations.get(userId);
    if(byConversation!=null)synchronized(byConversation){byConversation.remove(convId);}
    Deque<Topic> userTopics=topics.get(userId);
    if(userTopics!=null)synchronized(userTopics){userTopics.removeIf(topic->topic.conversationId().equals(convId));}
  }

  /** Memory block injected into the system prompt: only this user's own earlier chats. */
  public String memoryBlock(String userId, String currentConvId) {
    var t = topics.get(userId);
    if (t == null) return "";
    synchronized (t) { return String.join("\n", t.stream().map(Topic::text).toList()); }
  }
}
