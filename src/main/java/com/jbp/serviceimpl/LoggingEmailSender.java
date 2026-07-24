package com.jbp.serviceimpl;

import com.jbp.service.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Development email sender: logs the message instead of sending it. Replace with an
 * SMTP-backed implementation for production.
 */
@Slf4j
@Component
public class LoggingEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String body) {
        log.info("[EMAIL] to={} | subject='{}' | body='{}'", to, subject, body);
    }
}
