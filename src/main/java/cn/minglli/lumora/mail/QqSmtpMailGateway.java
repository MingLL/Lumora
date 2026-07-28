package cn.minglli.lumora.mail;

import jakarta.mail.internet.MimeMessage;
import cn.minglli.lumora.config.LumoraProperties;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class QqSmtpMailGateway implements MailGateway {

    private final JavaMailSender mailSender;
    private final LumoraProperties properties;

    public QqSmtpMailGateway(JavaMailSender mailSender, LumoraProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(MailRequest request) {
        try {
            MimeMessage mime = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(properties.getMailUsername(), properties.getMailFromName());
            helper.setTo(request.recipients().toArray(String[]::new));
            helper.setSubject(request.subject());
            helper.setText(request.textBody(), request.htmlBody());
            mime.setHeader("Message-ID", request.stableMessageId());
            mailSender.send(mime);
        } catch (Exception exception) {
            throw new MailDeliveryException(MailErrorSanitizer.sanitize(exception), exception);
        }
    }

    public static final class MailDeliveryException extends RuntimeException {
        public MailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
