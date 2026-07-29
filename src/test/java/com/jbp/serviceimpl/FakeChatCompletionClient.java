package com.jbp.serviceimpl;

import com.jbp.service.ChatCompletionClient;

/**
 * Stand-in {@link ChatCompletionClient} for testing the decorators and the task pipeline without
 * any network access. Counts calls so a test can prove a decorator stopped a call from reaching
 * the provider, and records what was sent so a test can prove the prompt was assembled correctly.
 */
class FakeChatCompletionClient implements ChatCompletionClient {

    private final String reply;
    private final RuntimeException failure;
    private int callCount;
    private String lastSystemPrompt;
    private String lastUserMessage;

    private FakeChatCompletionClient(String reply, RuntimeException failure) {
        this.reply = reply;
        this.failure = failure;
    }

    static FakeChatCompletionClient replyingWith(String reply) {
        return new FakeChatCompletionClient(reply, null);
    }

    static FakeChatCompletionClient failingWith(RuntimeException failure) {
        return new FakeChatCompletionClient(null, failure);
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        callCount++;
        lastSystemPrompt = systemPrompt;
        lastUserMessage = userMessage;
        if (failure != null) {
            throw failure;
        }
        return reply;
    }

    int callCount() {
        return callCount;
    }

    String lastSystemPrompt() {
        return lastSystemPrompt;
    }

    String lastUserMessage() {
        return lastUserMessage;
    }
}
