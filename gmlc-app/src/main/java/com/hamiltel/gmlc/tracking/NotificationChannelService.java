package com.hamiltel.gmlc.tracking;

import com.hamiltel.gmlc.audit.AuditTrail;
import com.hamiltel.gmlc.ops.OpsBadRequestException;
import com.hamiltel.gmlc.ops.OpsConflictException;
import com.hamiltel.gmlc.ops.OpsNotFoundException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Notification channels. The signing secret is generated here, sealed with the
 * deployment key and returned once; it is not readable from any endpoint
 * afterwards.
 *
 * <p>Only WEBHOOK is built. EMAIL, TELEGRAM and SLACK are refused with a
 * stable code rather than accepted and silently dropped, and no credential is
 * stored for a notifier this deployment cannot deliver through.
 */
@Service
public class NotificationChannelService {

    static final String NOT_AVAILABLE = "NOTIFIER_NOT_AVAILABLE";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final NotificationChannelRepository channels;
    private final SecretBox secrets;
    private final AuditTrail audit;

    public NotificationChannelService(NotificationChannelRepository channels, SecretBox secrets,
                                      AuditTrail audit) {
        this.channels = channels;
        this.secrets = secrets;
        this.audit = audit;
    }

    public Created create(long tenantId, UUID actorUserId, Command command) {
        String type = command.type() == null ? "" : command.type().trim().toUpperCase();
        if (!"WEBHOOK".equals(type)) {
            throw new OpsBadRequestException(NOT_AVAILABLE, "only WEBHOOK delivery is built in"
                    + " this deployment; " + (type.isEmpty() ? "no type was given" : type)
                    + " channels cannot be created");
        }
        if (!secrets.configured()) {
            throw new OpsBadRequestException("NOTIFIER_KEY_NOT_CONFIGURED",
                    "gmlc.tracking.notifier-key is blank, so a signing secret cannot be"
                            + " stored; set the deployment key before creating a channel");
        }
        String url = command.url() == null ? "" : command.url().trim();
        requireHttpUrl(url);
        String name = command.name() == null ? "" : command.name().trim();
        if (channels.existsName(tenantId, name)) {
            throw new OpsConflictException("CHANNEL_ALREADY_EXISTS",
                    "a channel with that name already exists");
        }

        String secret = newSecret();
        UUID channelId = UUID.randomUUID();
        channels.insert(channelId, tenantId, name, type, url, secrets.seal(secret),
                !Boolean.FALSE.equals(command.enabled()), actorUserId);
        audit.recordOperator(tenantId, actorUserId, "NOTIFICATION_CHANNEL_ADDED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("channelId", channelId.toString(), "type", type));
        return new Created(find(tenantId, channelId), secret,
                "Store this signing secret now: it is not shown again.");
    }

    public List<NotificationChannelRepository.ChannelView> list(long tenantId) {
        return channels.list(tenantId);
    }

    public NotificationChannelRepository.ChannelView find(long tenantId, UUID channelId) {
        return channels.find(tenantId, channelId)
                .orElseThrow(() -> new OpsNotFoundException("notification channel"));
    }

    public NotificationChannelRepository.ChannelView setEnabled(long tenantId, UUID actorUserId,
                                                               UUID channelId, boolean enabled) {
        find(tenantId, channelId);
        channels.setEnabled(tenantId, channelId, enabled);
        audit.recordOperator(tenantId, actorUserId, "NOTIFICATION_CHANNEL_UPDATED",
                AuditTrail.Outcome.ALLOWED,
                Map.of("channelId", channelId.toString(), "enabled", enabled));
        return find(tenantId, channelId);
    }

    public void delete(long tenantId, UUID actorUserId, UUID channelId) {
        NotificationChannelRepository.ChannelView channel = find(tenantId, channelId);
        if (channel.schedules() > 0) {
            throw new OpsConflictException("CHANNEL_IN_USE",
                    "detach the schedules that notify through this channel first");
        }
        channels.delete(tenantId, channelId);
        audit.recordOperator(tenantId, actorUserId, "NOTIFICATION_CHANNEL_REMOVED",
                AuditTrail.Outcome.ALLOWED, Map.of("channelId", channelId.toString()));
    }

    private static void requireHttpUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new OpsBadRequestException("NOTIFIER_URL_INVALID",
                    "url must be an absolute http or https URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) {
            throw new OpsBadRequestException("NOTIFIER_URL_INVALID",
                    "url must be an absolute http or https URL");
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public record Command(String name, String type, String url, Boolean enabled) {
    }

    public record Created(NotificationChannelRepository.ChannelView channel, String secret,
                          String secretNotice) {
    }
}
