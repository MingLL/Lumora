package cn.minglli.lumora.mail;

import java.util.regex.Pattern;

final class MailErrorSanitizer {

    private static final int MAX_LENGTH = 500;

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern AUTH_CODE = Pattern.compile("(?i)(auth.?code|password|passwd|secret|token)\\s*[:=]\\s*\\S+");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)://[^@/@\\s]+:[^@/@\\s]+@");

    private MailErrorSanitizer() {
    }

    static String sanitize(Throwable error) {
        String className = error.getClass().getName();
        String message = error.getMessage() == null ? "" : error.getMessage();
        String combined = className + ": " + message;
        String filtered = EMAIL.matcher(combined).replaceAll("<email>");
        filtered = AUTH_CODE.matcher(filtered).replaceAll("<redacted>");
        filtered = URL_CREDENTIALS.matcher(filtered).replaceAll("://<redacted>@");
        if (filtered.length() > MAX_LENGTH) {
            filtered = filtered.substring(0, MAX_LENGTH);
        }
        return filtered;
    }
}
