package cn.minglli.lumora.mail;

import java.util.List;

public interface MailGateway {

    void send(MailRequest request);

    record MailRequest(
            List<String> recipients,
            String subject,
            String htmlBody,
            String textBody,
            String stableMessageId) {
    }
}
