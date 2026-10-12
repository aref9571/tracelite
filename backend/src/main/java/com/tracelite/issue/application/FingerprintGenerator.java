package com.tracelite.issue.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class FingerprintGenerator {

    public static final int MAX_FRAMES = 30;

    private static final String ALGORITHM = "SHA-256";

    private static final Pattern JAVA_FRAME = Pattern.compile("^\\s*at\\s+(?:[^\\s/(]*/)*([^\\s/(]+)\\(.*\\)\\s*$");
    private static final Pattern HIDDEN_CLASS_SUFFIX = Pattern.compile("/0x[0-9a-fA-F]+");
    private static final List<Replacement> GENERATED_NAME_PARTS = List.of(
            new Replacement("\\$\\$Lambda\\$\\d+", "\\$\\$Lambda"),
            new Replacement("(lambda\\$[^$.]+)\\$\\d+", "$1"),
            new Replacement("^jdk\\.proxy\\d+\\.", "jdk.proxy."),
            new Replacement("\\$Proxy\\d+", "\\$Proxy"),
            new Replacement("(Generated\\w*Accessor)\\d+", "$1"),
            new Replacement("\\$\\$(\\w*CGLIB)\\$\\$\\w+", "\\$\\$$1"),
            new Replacement("\\$HibernateProxy\\$\\w+", "\\$HibernateProxy"));

    public String generate(String exceptionType, String stackTrace) {
        Objects.requireNonNull(exceptionType, "exceptionType must not be null");
        MessageDigest digest = sha256();
        update(digest, exceptionType.strip());
        normalizedFrames(stackTrace).forEach(frame -> update(digest, frame));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static List<String> normalizedFrames(String stackTrace) {
        List<String> frames = new ArrayList<>();
        if (stackTrace == null) {
            return frames;
        }
        for (String line : stackTrace.lines().toList()) {
            if (startsNestedThrowable(line)) {
                break;
            }
            String frame = normalizedFrame(line);
            if (frame != null && (frames.isEmpty() || !frames.getLast().equals(frame))) {
                frames.add(frame);
                if (frames.size() == MAX_FRAMES) {
                    break;
                }
            }
        }
        return frames;
    }

    private static boolean startsNestedThrowable(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:");
    }

    private static String normalizedFrame(String line) {
        Matcher matcher = JAVA_FRAME.matcher(HIDDEN_CLASS_SUFFIX.matcher(line).replaceAll(""));
        if (!matcher.matches()) {
            return null;
        }
        String method = matcher.group(1);
        for (Replacement replacement : GENERATED_NAME_PARTS) {
            method = replacement.applyTo(method);
        }
        return method;
    }

    private static void update(MessageDigest digest, String part) {
        digest.update((part.length() + ":" + part + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(ALGORITHM + " is not available", exception);
        }
    }

    private record Replacement(Pattern pattern, String replacement) {

        Replacement(String regex, String replacement) {
            this(Pattern.compile(regex), replacement);
        }

        String applyTo(String value) {
            return pattern.matcher(value).replaceAll(replacement);
        }
    }
}
