package com.flytbase.copilot;

import com.flytbase.copilot.chat.MemoryStore;
import com.flytbase.copilot.core.*;
import java.nio.file.Path;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CopilotApplication {
  public static void main(String[] args) { SpringApplication.run(CopilotApplication.class, args); }

  @Bean Agent.Llm llm(@Value("${copilot.ollama-url}") String url, @Value("${copilot.model}") String model, @Value("${copilot.num-ctx:16384}") int ctx) { return new OllamaClient(url, model, ctx); }
  @Bean MemoryStore memoryStore() { return new MemoryStore(); }
}
