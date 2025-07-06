package com.devoxx.genie.service.prompt.response.nonstreaming;

import com.devoxx.genie.model.enumarations.ModelProvider;
import com.devoxx.genie.model.mcp.MCPServer;
import com.devoxx.genie.model.request.ChatMessageContext;
import com.devoxx.genie.service.FileListManager;
import com.devoxx.genie.service.mcp.MCPExecutionService;
import com.devoxx.genie.service.mcp.MCPService;
import com.devoxx.genie.service.prompt.error.ExecutionException;
import com.devoxx.genie.service.prompt.error.ModelException;
import com.devoxx.genie.service.prompt.error.PromptErrorHandler;
import com.devoxx.genie.service.prompt.memory.ChatMemoryManager;
import com.devoxx.genie.service.prompt.threading.ThreadPoolManager;
import com.devoxx.genie.ui.settings.DevoxxGenieStateService;
import com.devoxx.genie.util.TemplateVariableEscaper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

import dev.langchain4j.service.tool.ToolProvider;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class NonStreamingPromptExecutionService {

    @Getter
    private volatile boolean running = false;
    
    private final ChatMemoryManager chatMemoryManager;
    private final ThreadPoolManager threadPoolManager;
    private final AtomicReference<CompletableFuture<ChatResponse>> currentQueryFuture = new AtomicReference<>();

    public NonStreamingPromptExecutionService() {
        this.chatMemoryManager = ChatMemoryManager.getInstance();
        this.threadPoolManager = ThreadPoolManager.getInstance();
    }

    @NotNull
    public static NonStreamingPromptExecutionService getInstance() {
        return ApplicationManager.getApplication().getService(NonStreamingPromptExecutionService.class);
    }

    /**
     * Execute the query with the given language text pair and chat language model.
     *
     * @param chatMessageContext the chat message context
     * @return the response future
     */
    public @NotNull CompletableFuture<ChatResponse> executeQuery(@NotNull ChatMessageContext chatMessageContext) {
        log.debug("Execute query : {}", chatMessageContext);

        // Cancel any existing query
        cancelExecutingQuery();
        
        Project project = chatMessageContext.getProject();

        long startTime = System.currentTimeMillis();
        running = true;

        // Create new future for the current query
        CompletableFuture<ChatResponse> queryFuture = CompletableFuture
            .supplyAsync(
                () -> processChatMessage(chatMessageContext), 
                threadPoolManager.getPromptExecutionPool()
            )
            .orTimeout(
                chatMessageContext.getTimeout() == null ? 60 : chatMessageContext.getTimeout(), 
                TimeUnit.SECONDS
            )
            .thenApply(result -> {
                chatMessageContext.setExecutionTimeMs(System.currentTimeMillis() - startTime);
                return result;
            })
            .exceptionally(throwable -> {
                // Ignore cancellation exceptions
                if (!(throwable instanceof CancellationException) &&
                    !(throwable.getCause() instanceof CancellationException)) {
                    // Create a specific execution exception and handle it with our standardized handler
                    ExecutionException executionError = new ExecutionException(
                        "Error occurred while processing chat message", throwable);
                    PromptErrorHandler.handleException(project, executionError, chatMessageContext);
                }
                // The PromptErrorHandler will handle appropriate recovery like removing failed exchanges
                return null;
            })
            .whenComplete((response, throwable) -> {
                // Always clear the current future reference when done
                currentQueryFuture.set(null);
                running = false;
                
                // Add file references if any, similar to StreamingResponseHandler
                if (response != null && !FileListManager.getInstance().isEmpty(project)) {
                    log.debug("Adding file references for non-streaming response");
                    // Store the file references in the context for later use
                    chatMessageContext.setFileReferences(FileListManager.getInstance().getFiles(project));
                }
            });
        
        // Store the future for potential cancellation
        currentQueryFuture.set(queryFuture);
        
        return queryFuture;
    }

    /**
     * Cancel the currently executing query if one exists.
     */
    public void cancelExecutingQuery() {
        CompletableFuture<ChatResponse> future = currentQueryFuture.get();
        if (future != null && !future.isDone()) {
            future.cancel(true);
            if (MCPService.isMCPEnabled()) {
                MCPExecutionService.getInstance().clearClientCache();
            }
            currentQueryFuture.set(null);
            running = false;
        }
    }

    /**
     * Process the chat message and generate a response.
     */
    private @NotNull ChatResponse processChatMessage(ChatMessageContext chatMessageContext) {
        try {
            Project project = chatMessageContext.getProject();
            ChatLanguageModel chatLanguageModel = chatMessageContext.getChatLanguageModel();

            String projectId = project.getLocationHash();

            ChatMemory chatMemory = chatMemoryManager.getChatMemory(projectId);

            Assistant assistant = buildAssistant(chatLanguageModel, chatMemory);

            if (MCPService.isMCPEnabled()) {
                Map<String, MCPServer> mcpServers = DevoxxGenieStateService.getInstance().getMcpSettings().getMcpServers();
                int totalActiveMCPTools = mcpServers.values().stream()
                        .filter(MCPServer::isEnabled)
                        .mapToInt(server -> server.getAvailableTools().size())
                        .sum();

                // If MCP is enable and we active tools then recreate assistant
                if (totalActiveMCPTools > 0) {
                    MCPService.logDebug("MCP is enabled and we have active tools. Creating MCP tool provider");

                    // Use project-specific tool provider with filesystem access
                    ToolProvider mcpToolProvider = MCPExecutionService.getInstance().createMCPToolProvider(project);

                    if (mcpToolProvider != null) {
                        MCPService.logDebug("Successfully created MCP tool provider with filesystem access");

//                        // Add file references to context before processing if we have them
//                        if (!FileListManager.getInstance().isEmpty(project)) {
//                            chatMessageContext.setFileReferences(FileListManager.getInstance().getFiles(project));
//                            MCPService.logDebug("Added file references to MCP context: " +
//                                    FileListManager.getInstance().getFiles(project).size() + " files");
//                        }

                        assistant = AiServices.builder(Assistant.class)
                                .chatLanguageModel(chatLanguageModel)
                                .chatMemoryProvider(memoryId -> chatMemory)
                                .systemMessageProvider(memoryId -> DevoxxGenieStateService.getInstance().getSystemPrompt())
                                .toolProvider(mcpToolProvider)
                                .build();
                    }
                }
            }

            String userMessage = chatMessageContext.getUserMessage().singleText();
            String cleanText = TemplateVariableEscaper.escape(userMessage);

            String queryResponse = assistant.chat(cleanText);

            return ChatResponse.builder()
                    .aiMessage(AiMessage.aiMessage(queryResponse))
                    .build();

        } catch (Exception e) {
            // Thread interruption is likely from cancellation, so we handle it specially
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Query was cancelled");
            }
            
            log.error(e.getMessage());
            if (chatMessageContext.getLanguageModel().getProvider().equals(ModelProvider.Jan)) {
                // Use our own ModelException instead of the generic ModelNotActiveException
                throw new ModelException(
                    "Selected Jan model is not active. Download and make it active or add API Key in Jan settings.", e);
            }

            // Let the ChatMemoryManager handle removing the last message on error
            chatMemoryManager.removeLastMessage(chatMessageContext.getProject());

            // Use our own ModelException instead of the generic ProviderUnavailableException
            throw new ModelException("Provider unavailable: " + e.getMessage(), e);
        }
    }

    private static Assistant buildAssistant(ChatLanguageModel chatLanguageModel, ChatMemory chatMemory) {
        return AiServices.builder(Assistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId -> chatMemory)
                .systemMessageProvider(memoryId -> DevoxxGenieStateService.getInstance().getSystemPrompt())
                .build();
    }

    /**
     * The Code Assistant chat method
     */
    interface Assistant {
        String chat(String userMessage);
    }
}
