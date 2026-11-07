package org.minecraft.hatlas.proxy.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.Map;

/**
 * Renders the MiniMessage templates of {@code config.yml}.
 * <p>
 * URLs ({@code <link_url>}, {@code <renew_url>}) come from the admin's config and are also used
 * inside tag arguments ({@code <click:open_url:'<link_url>'>}), so they are put in as text before
 * parsing. Every other value (codes, numbers) goes through an unparsed placeholder and can never
 * be read as a tag.
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Map<String, String> urls;

    /** @param urls placeholder name (without brackets) → URL from the config */
    public Messages(Map<String, String> urls) {
        this.urls = Map.copyOf(urls);
    }

    public Component render(String template, Map<String, String> values) {
        String text = template;
        for (var url : urls.entrySet()) {
            text = text.replace("<" + url.getKey() + ">", url.getValue().replace("'", "\\'"));
        }
        TagResolver.Builder resolvers = TagResolver.builder();
        values.forEach((name, value) -> resolvers.resolver(Placeholder.unparsed(name, value)));
        return MINI.deserialize(text, resolvers.build());
    }

    public Component render(String template) {
        return render(template, Map.of());
    }
}
