package org.minecraft.hatlas.proxy.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagesTest {

    private final Messages messages = new Messages(Map.of("link_url", "https://example.org/link"));

    @Test
    void defaultLinkCodeMessageShowsCodeMinutesAndClickableUrl() {
        String template = "<green>Your link code: <gold><bold><code></bold></gold> (valid <minutes> min). "
                + "Enter it on <click:open_url:'<link_url>'><u><link_url></u></click>";

        Component c = messages.render(template, Map.of("code", "AB23CD", "minutes", "10"));

        assertEquals("Your link code: AB23CD (valid 10 min). Enter it on https://example.org/link",
                PlainTextComponentSerializer.plainText().serialize(c));
        assertTrue(clicks(c).contains("https://example.org/link"), "click opens the link page");
    }

    @Test
    void valuesAreNeverParsedAsTags() {
        Component c = messages.render("<code>", Map.of("code", "<red>X"));

        assertEquals("<red>X", PlainTextComponentSerializer.plainText().serialize(c));
    }

    private static List<String> clicks(Component c) {
        List<String> urls = new ArrayList<>();
        ClickEvent<?> click = c.clickEvent();
        if (click != null && click.payload() instanceof ClickEvent.Payload.Text text) {
            urls.add(text.value());
        }
        c.children().forEach(child -> urls.addAll(clicks(child)));
        return urls;
    }
}
