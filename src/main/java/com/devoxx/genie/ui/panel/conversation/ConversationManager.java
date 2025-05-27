package com.devoxx.genie.ui.panel.conversation;

import com.devoxx.genie.model.Constant;
import com.devoxx.genie.model.conversation.Conversation;
import com.devoxx.genie.model.request.ChatMessageContext;
import com.devoxx.genie.service.ChatService;
import com.devoxx.genie.service.FileListManager;
import com.devoxx.genie.service.prompt.memory.ChatMemoryService;
import com.devoxx.genie.ui.listener.ConversationEventListener;
import com.devoxx.genie.ui.listener.ConversationSelectionListener;
import com.devoxx.genie.ui.listener.ConversationStarter;
import com.devoxx.genie.ui.panel.PromptOutputPanel;
import com.devoxx.genie.ui.panel.PromptPanelRegistry;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ResourceBundle;

import static com.devoxx.genie.ui.util.TimestampUtil.getCurrentTimestamp;

/**
 * Manages conversation operations like starting new conversations,
 * handling conversation selection, and managing conversation state.
 */
@Slf4j
public class ConversationManager implements ConversationEventListener, ConversationSelectionListener, ConversationStarter {
    private final Project project;
    private final ChatService chatService;
    private final ConversationHistoryManager historyManager;
    private final MessageRenderer messageRenderer;
    private final JLabel conversationLabel;
    private static final int MAX_TITLE_LENGTH = 50;
    private final Runnable webViewRefreshCallback;
    
    // Track the current active conversation
    private Conversation currentConversation;

    /**
     * Creates a new conversation manager.
     *
     * @param project The active project
     * @param chatService The chat service
     * @param historyManager The history manager
     * @param messageRenderer The message renderer
     * @param conversationLabel The conversation label to update
     */
    public ConversationManager(Project project, 
                              ChatService chatService,
                              ConversationHistoryManager historyManager,
                              MessageRenderer messageRenderer,
                              JLabel conversationLabel) {
        this(project, chatService, historyManager, messageRenderer, conversationLabel, null);
    }

    /**
     * Creates a new conversation manager with webview refresh callback.
     *
     * @param project The active project
     * @param chatService The chat service
     * @param historyManager The history manager
     * @param messageRenderer The message renderer
     * @param conversationLabel The conversation label to update
     * @param webViewRefreshCallback Optional callback to refresh webview
     */
    public ConversationManager(Project project, 
                              ChatService chatService,
                              ConversationHistoryManager historyManager,
                              MessageRenderer messageRenderer,
                              JLabel conversationLabel,
                              Runnable webViewRefreshCallback) {
        this.project = project;
        this.chatService = chatService;
        this.historyManager = historyManager;
        this.messageRenderer = messageRenderer;
        this.conversationLabel = conversationLabel;
        this.webViewRefreshCallback = webViewRefreshCallback;
    }
    /**
     * Get the current active conversation.
     * 
     * @return The current conversation, or null if none is active
     */
    public Conversation getCurrentConversation() {
        return currentConversation;
    }
    
    /**
     * Set the current active conversation.
     * 
     * @param conversation The conversation to set as current
     */
    public void setCurrentConversation(Conversation conversation) {
        this.currentConversation = conversation;
    }

    /**
     * Start a new conversation.
     * Clear the conversation panel, prompt input area, prompt output panel, file list and chat memory.
     * Also check for and recover from black screen issues.
     */
    @Override
    public void startNewConversation() {
        // Clear everything for a new conversation - this is the correct behavior
        FileListManager.getInstance().clear(project);
        ChatMemoryService.getInstance().clearMemory(project);
        
        // Clear the current conversation state
        currentConversation = null;

        chatService.startNewConversation("");

        // Check for black screen issues and recover if needed before showing new content
        ApplicationManager.getApplication().invokeLater(() -> {
            updateNewConversationLabel();
            
            // Check if we need to recover from black screen before proceeding
            boolean needsRecovery = checkForBlackScreenAndRecover();
            
            if (needsRecovery) {
                log.info("Black screen detected, performing recovery before starting new conversation");
                // Recovery will refresh the webview and then show welcome content
                triggerWebViewRefresh();
                
                // Schedule welcome content after recovery completes
                Timer welcomeTimer = new Timer(2000, e -> {
                    messageRenderer.clear();
                    ResourceBundle resourceBundle = ResourceBundle.getBundle(Constant.MESSAGES);
                    messageRenderer.showWelcome(resourceBundle);
                });
                welcomeTimer.setRepeats(false);
                welcomeTimer.start();
            } else {
                // Normal flow - no recovery needed
                messageRenderer.clear();
                ResourceBundle resourceBundle = ResourceBundle.getBundle(Constant.MESSAGES);
                messageRenderer.showWelcome(resourceBundle);
            }
            
            // Make sure all panels know this is a new conversation
            for (PromptOutputPanel panel : PromptPanelRegistry.getInstance().getPanels(project)) {
                // The clear() method already resets isNewConversation = true
                panel.clear();
            }
        });
    }
    /**
     * Handle selection of a conversation from history.
     *
     * @param conversation The selected conversation
     */
    @Override
    public void onConversationSelected(@NotNull Conversation conversation) {
        // Set this as the current active conversation
        currentConversation = conversation;

        String displayTitle = conversation.getTitle().length() > MAX_TITLE_LENGTH ?
                conversation.getTitle().substring(0, MAX_TITLE_LENGTH) + "..." :
                conversation.getTitle();

        // Update the conversation label with the title
        conversationLabel.setText(displayTitle);

        // Clear the current conversation in the web view without showing welcome screen
        messageRenderer.clearWithoutWelcome();

        // Mark this as not a new conversation in any panel that might be registered
        for (PromptOutputPanel panel : PromptPanelRegistry.getInstance().getPanels(project)) {
            panel.markConversationAsStarted();
        }

        // Restore all messages for this conversation
        historyManager.restoreConversation(conversation);
    }

    /**
     * Update the conversation label with new timestamp.
     */
    public void updateNewConversationLabel() {
        conversationLabel.setText("New conversation " + getCurrentTimestamp());
    }

    /**
     * Handle notification when a new conversation is created.
     *
     * @param chatMessageContext The context of the chat message
     */
    @Override
    public void onNewConversation(ChatMessageContext chatMessageContext) {
        // Reload the conversation history when a new conversation is created
        ApplicationManager.getApplication().invokeLater(() -> {
            historyManager.loadConversationHistory();
            log.debug("Conversation history reloaded after new conversation event");
        });
    }
    
    /**
     * Check for black screen issues and determine if recovery is needed.
     * This is called when starting a new conversation to ensure the webview is in a good state.
     * 
     * @return true if recovery was needed and triggered, false if no issues detected
     */
    private boolean checkForBlackScreenAndRecover() {
        try {
            // Ask the message renderer to check for black screen issues
            if (messageRenderer != null && messageRenderer.hasBlackScreenIssues()) {
                log.warn("Black screen issues detected, recovery needed");
                return true;
            }
            
            log.debug("No black screen issues detected");
            return false;
            
        } catch (Exception e) {
            log.error("Error checking for black screen issues", e);
            // If we can't check properly, assume recovery is needed to be safe
            return true;
        }
    }
    
    /**
     * Manually trigger webview refresh if callback is available.
     * This can be called when webview issues are suspected.
     */
    public void triggerWebViewRefresh() {
        if (webViewRefreshCallback != null) {
            log.info("Manually triggering webview refresh");
            webViewRefreshCallback.run();
        } else {
            log.debug("No webview refresh callback available");
        }
    }}
