package lol.pyr.znpcsplus.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.command.CommandSender;

import java.lang.reflect.Method;

/**
 * Delivers a component through the server's OWN Adventure implementation rather than through
 * adventure-platform's BukkitAudiences.
 *
 * <p>BukkitAudiences picks a "facet" to send with. The facets that can carry a real component are
 * the NMS one and the Spigot one, and the Spigot one needs the Bungee chat API
 * ({@code net.md_5.bungee.api.chat.BaseComponent}). Modern Paper derivatives have removed that API
 * entirely, so on those servers the platform silently falls back to
 * {@code CommandSender#sendMessage(String)} with the component run through the LEGACY serializer.
 * Legacy text can carry colours but has nowhere to put a click or hover event, so buttons still
 * render and are simply dead. That is what broke [EDIT] and [DELETE] on {@code /npc action list},
 * and the [TELEPORT] buttons on {@code /npc list} and {@code /npc near} with it.
 *
 * <p>On every server new enough to have dropped the Bungee API, {@code CommandSender} is itself an
 * Adventure {@code Audience}, so the component can simply be handed to it. The catch is that our
 * Adventure is relocated at build time and the server's is not, so the two {@code Component}
 * classes are unrelated types. JSON is the bridge: serialize with our copy, deserialize with
 * theirs.
 *
 * <p>Every server-side class is looked up by a name assembled at runtime. A plain string literal
 * would be rewritten by the shadow plugin's relocator exactly like the import above it, and would
 * then point at our own relocated copy instead of the server's.
 */
public final class NativeAdventure {
    private static final Object SERIALIZER;
    private static final Method DESERIALIZE;
    private static final Method SEND_COMPONENT;

    static {
        Object serializer = null;
        Method deserialize = null;
        Method send = null;
        try {
            Class<?> nativeComponent = Class.forName(name("net", "kyori", "adventure", "text", "Component"));
            for (Method method : CommandSender.class.getMethods()) {
                if (!"sendMessage".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].equals(nativeComponent)) {
                    send = method;
                    break;
                }
            }
            if (send != null) {
                // Gson first because every version ships it; the json one is the newer spelling
                Object[] found = findSerializer(
                        name("net", "kyori", "adventure", "text", "serializer", "gson", "GsonComponentSerializer"), "gson");
                if (found == null) found = findSerializer(
                        name("net", "kyori", "adventure", "text", "serializer", "json", "JSONComponentSerializer"), "json");
                if (found != null) {
                    serializer = found[0];
                    deserialize = (Method) found[1];
                }
            }
        } catch (Throwable ignored) {
            // Any failure at all means the server is not one we can send natively to
        }
        if (serializer == null || deserialize == null) send = null;
        SERIALIZER = serializer;
        DESERIALIZE = deserialize;
        SEND_COMPONENT = send;
    }

    private NativeAdventure() {
    }

    /**
     * @return true when components can be handed to the server's own Adventure
     */
    public static boolean isSupported() {
        return SEND_COMPONENT != null;
    }

    /**
     * Sends the component natively, keeping its click and hover events intact.
     *
     * @return false if this server cannot be sent to natively, or the attempt failed, in which case
     * the caller should fall back to its normal audience
     */
    public static boolean send(CommandSender sender, Component message) {
        if (sender == null || message == null || SEND_COMPONENT == null) return false;
        try {
            String json = GsonComponentSerializer.gson().serialize(message);
            SEND_COMPONENT.invoke(sender, DESERIALIZE.invoke(SERIALIZER, json));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object[] findSerializer(String className, String factoryMethod) {
        try {
            Class<?> type = Class.forName(className);
            Object instance = type.getMethod(factoryMethod).invoke(null);
            if (instance == null) return null;
            for (Method method : type.getMethods()) {
                if (!"deserialize".equals(method.getName())) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].equals(String.class)) {
                    return new Object[]{instance, method};
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String name(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (builder.length() > 0) builder.append('.');
            builder.append(part);
        }
        return builder.toString();
    }
}
