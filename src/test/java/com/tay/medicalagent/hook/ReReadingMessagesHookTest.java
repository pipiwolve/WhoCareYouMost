package com.tay.medicalagent.hook;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReReadingMessagesHookTest {

    private final ReReadingMessagesHook hook = new ReReadingMessagesHook();

    @Test
    void shouldRewriteOnlyLatestUserMessage() {
        List<Message> originalMessages = List.of(
                new SystemMessage("system"),
                new UserMessage("第一个问题"),
                new AssistantMessage("中间回答"),
                new UserMessage("第二个问题")
        );

        List<Message> rewrittenMessages = hook.rewriteMessages(originalMessages);

        assertNotSame(originalMessages, rewrittenMessages);
        assertEquals("第一个问题", ((UserMessage) rewrittenMessages.get(1)).getText());
        assertTrue(((UserMessage) rewrittenMessages.get(3)).getText().contains("Read the question again: 第二个问题"));
        assertTrue(Boolean.TRUE.equals(
                ((UserMessage) rewrittenMessages.get(3)).getMetadata().get(ReReadingMessagesHook.RE_READING_APPLIED_METADATA_KEY)));
    }

    @Test
    void shouldNotRewriteSameUserMessageTwice() {
        List<Message> onceRewritten = hook.rewriteMessages(List.of(new UserMessage("头痛怎么办")));
        List<Message> twiceRewritten = hook.rewriteMessages(onceRewritten);

        assertEquals(((UserMessage) onceRewritten.get(0)).getText(), ((UserMessage) twiceRewritten.get(0)).getText());
    }
}
