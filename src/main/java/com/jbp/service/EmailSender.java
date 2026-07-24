package com.jbp.service;

/**
 * Sends an email. The dev implementation logs the message; a real SMTP sender can be
 * swapped in later behind this interface with no changes to callers.
 */
public interface EmailSender {

    void send(String to, String subject, String body);
}
