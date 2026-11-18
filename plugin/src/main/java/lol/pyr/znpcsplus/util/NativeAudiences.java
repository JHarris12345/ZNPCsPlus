package lol.pyr.znpcsplus.util;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.audience.ForwardingAudience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Wraps a {@link BukkitAudiences} so that chat messages aimed at a specific sender go through the
 * server's own Adventure, which keeps click and hover events, instead of adventure-platform's
 * legacy-text fallback which drops them. See {@link NativeAdventure} for why that fallback happens.
 *
 * <p>Everything other than a chat message to a known sender (titles, sounds, boss bars, the
 * broadcast audiences) is forwarded to the wrapped instance untouched, so this changes how messages
 * are delivered and nothing else. If the native path is unavailable, or fails at the moment of
 * sending, the wrapped audience is used exactly as before.
 */
public class NativeAudiences implements BukkitAudiences {
    private final BukkitAudiences delegate;

    private NativeAudiences(BukkitAudiences delegate) {
        this.delegate = delegate;
    }

    /**
     * @return the delegate itself on servers that cannot be sent to natively, so that older
     * platforms keep their existing behaviour
     */
    public static BukkitAudiences wrap(BukkitAudiences delegate) {
        return NativeAdventure.isSupported() ? new NativeAudiences(delegate) : delegate;
    }

    @Override
    public Audience sender(CommandSender sender) {
        return new SenderAudience(sender, delegate.sender(sender));
    }

    @Override
    public Audience player(Player player) {
        return new SenderAudience(player, delegate.player(player));
    }

    @Override
    public Audience filter(Predicate<CommandSender> filter) {
        return delegate.filter(filter);
    }

    @Override
    public Audience all() {
        return delegate.all();
    }

    @Override
    public Audience console() {
        return delegate.console();
    }

    @Override
    public Audience players() {
        return delegate.players();
    }

    @Override
    public Audience player(UUID playerId) {
        return delegate.player(playerId);
    }

    @Override
    public Audience permission(String permission) {
        return delegate.permission(permission);
    }

    @Override
    public Audience world(Key world) {
        return delegate.world(world);
    }

    @Override
    public Audience server(String serverName) {
        return delegate.server(serverName);
    }

    @Override
    public ComponentFlattener flattener() {
        return delegate.flattener();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static final class SenderAudience implements ForwardingAudience.Single {
        private final CommandSender sender;
        private final Audience delegate;

        private SenderAudience(CommandSender sender, Audience delegate) {
            this.sender = sender;
            this.delegate = delegate;
        }

        @Override
        public Audience audience() {
            return delegate;
        }

        @Override
        public void sendMessage(Component message) {
            if (!NativeAdventure.send(sender, message)) delegate.sendMessage(message);
        }
    }
}
