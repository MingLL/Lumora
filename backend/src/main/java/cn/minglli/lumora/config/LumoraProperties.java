package cn.minglli.lumora.config;

import java.time.ZoneId;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "lumora")
public class LumoraProperties {

    @NotBlank(message = "WECHAT_APP_ID must not be blank")
    private String wechatAppId;

    @NotBlank(message = "WECHAT_ORIGINAL_ID must not be blank")
    private String wechatOriginalId;

    @NotBlank(message = "WECHAT_TOKEN must not be blank")
    private String wechatToken;

    @NotBlank(message = "WECHAT_AES_KEY must not be blank")
    private String wechatAesKey;

    @NotBlank(message = "WECHAT_APP_SECRET must not be blank")
    private String wechatAppSecret;

    @NotBlank(message = "POSTGRES_HOST must not be blank")
    private String postgresHost;

    @NotNull(message = "POSTGRES_PORT must be provided")
    @Min(value = 1, message = "POSTGRES_PORT must be between 1 and 65535")
    @Max(value = 65535, message = "POSTGRES_PORT must be between 1 and 65535")
    private Integer postgresPort;

    @NotBlank(message = "POSTGRES_DATABASE must not be blank")
    private String postgresDatabase;

    @NotBlank(message = "POSTGRES_USERNAME must not be blank")
    private String postgresUsername;

    @NotBlank(message = "POSTGRES_PASSWORD must not be blank")
    private String postgresPassword;

    @NotBlank(message = "MAIL_USERNAME must not be blank")
    @Email(message = "MAIL_USERNAME must be a valid email address")
    private String mailUsername;

    @NotBlank(message = "MAIL_AUTH_CODE must not be blank")
    private String mailAuthCode;

    private String mailFromName = "Lumora";

    @Valid
    @NotEmpty(message = "REPORT_RECIPIENTS must not be empty")
    private List<@NotBlank(message = "REPORT_RECIPIENTS must not contain blank addresses")
            @Email(message = "REPORT_RECIPIENTS must contain valid email addresses") String> reportRecipients;

    @NotBlank(message = "REPORT_ADMIN_KEY must not be blank")
    private String reportAdminKey;

    @NotNull
    private ZoneId zone = ZoneId.of("Asia/Shanghai");

    private boolean schedulingEnabled = true;

    private boolean reportRecoveryEnabled = true;

    private boolean retentionEnabled = true;

    private boolean internalSendEnabled = true;

    @NotBlank(message = "WORKER_READY_MARKER must not be blank")
    private String workerReadyMarker = "/tmp/lumora-worker-ready";

    public String getWechatAppId() {
        return wechatAppId;
    }

    public void setWechatAppId(String wechatAppId) {
        this.wechatAppId = wechatAppId;
    }

    public String getWechatOriginalId() {
        return wechatOriginalId;
    }

    public void setWechatOriginalId(String wechatOriginalId) {
        this.wechatOriginalId = wechatOriginalId;
    }

    public String getWechatToken() {
        return wechatToken;
    }

    public void setWechatToken(String wechatToken) {
        this.wechatToken = wechatToken;
    }

    public String getWechatAesKey() {
        return wechatAesKey;
    }

    public void setWechatAesKey(String wechatAesKey) {
        this.wechatAesKey = wechatAesKey;
    }

    public String getWechatAppSecret() {
        return wechatAppSecret;
    }

    public void setWechatAppSecret(String wechatAppSecret) {
        this.wechatAppSecret = wechatAppSecret;
    }

    public String getPostgresHost() {
        return postgresHost;
    }

    public void setPostgresHost(String postgresHost) {
        this.postgresHost = postgresHost;
    }

    public Integer getPostgresPort() {
        return postgresPort;
    }

    public void setPostgresPort(Integer postgresPort) {
        this.postgresPort = postgresPort;
    }

    public String getPostgresDatabase() {
        return postgresDatabase;
    }

    public void setPostgresDatabase(String postgresDatabase) {
        this.postgresDatabase = postgresDatabase;
    }

    public String getPostgresUsername() {
        return postgresUsername;
    }

    public void setPostgresUsername(String postgresUsername) {
        this.postgresUsername = postgresUsername;
    }

    public String getPostgresPassword() {
        return postgresPassword;
    }

    public void setPostgresPassword(String postgresPassword) {
        this.postgresPassword = postgresPassword;
    }

    public String getMailUsername() {
        return mailUsername;
    }

    public void setMailUsername(String mailUsername) {
        this.mailUsername = mailUsername;
    }

    public String getMailAuthCode() {
        return mailAuthCode;
    }

    public void setMailAuthCode(String mailAuthCode) {
        this.mailAuthCode = mailAuthCode;
    }

    public String getMailFromName() {
        return mailFromName;
    }

    public void setMailFromName(String mailFromName) {
        this.mailFromName = mailFromName;
    }

    public List<String> getReportRecipients() {
        return reportRecipients;
    }

    public void setReportRecipients(List<String> reportRecipients) {
        this.reportRecipients = reportRecipients;
    }

    public String getReportAdminKey() {
        return reportAdminKey;
    }

    public void setReportAdminKey(String reportAdminKey) {
        this.reportAdminKey = reportAdminKey;
    }

    public ZoneId getZone() {
        return zone;
    }

    public void setZone(ZoneId zone) {
        this.zone = zone;
    }

    public boolean isSchedulingEnabled() {
        return schedulingEnabled;
    }

    public void setSchedulingEnabled(boolean schedulingEnabled) {
        this.schedulingEnabled = schedulingEnabled;
    }

    public boolean isReportRecoveryEnabled() {
        return reportRecoveryEnabled;
    }

    public void setReportRecoveryEnabled(boolean reportRecoveryEnabled) {
        this.reportRecoveryEnabled = reportRecoveryEnabled;
    }

    public boolean isRetentionEnabled() {
        return retentionEnabled;
    }

    public void setRetentionEnabled(boolean retentionEnabled) {
        this.retentionEnabled = retentionEnabled;
    }

    public boolean isInternalSendEnabled() {
        return internalSendEnabled;
    }

    public void setInternalSendEnabled(boolean internalSendEnabled) {
        this.internalSendEnabled = internalSendEnabled;
    }

    public String getWorkerReadyMarker() {
        return workerReadyMarker;
    }

    public void setWorkerReadyMarker(String workerReadyMarker) {
        this.workerReadyMarker = workerReadyMarker;
    }
}
