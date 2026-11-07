package org.minecraft.hatlas.proxy.link;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import org.minecraft.hatlas.proxy.config.HatlasConfig;
import org.minecraft.hatlas.proxy.message.Messages;
import org.slf4j.Logger;

import java.util.Map;

/**
 * {@code /link}, on the hub only: gives the player a single-use code to type on the website,
 * where they are already logged in. The website checks the code and records the link; this
 * plugin only writes the code (CLAUDE.md §1).
 */
public final class LinkCommand implements SimpleCommand {

    private final HatlasConfig config;
    private final Messages messages;
    private final LinkCodeStore store;
    private final Cooldown cooldown;
    private final Logger logger;

    public LinkCommand(HatlasConfig config, Messages messages, LinkCodeStore store, Cooldown cooldown,
                       Logger logger) {
        this.config = config;
        this.messages = messages;
        this.store = store;
        this.cooldown = cooldown;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        HatlasConfig.Messages text = config.messages();
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(messages.render(text.linkPlayersOnly()));
            return;
        }
        boolean onHub = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equals(config.servers().hub()))
                .orElse(false);
        if (!onHub) {
            player.sendMessage(messages.render(text.linkHubOnly()));
            return;
        }
        long wait = cooldown.tryAcquire(player.getUniqueId());
        if (wait > 0) {
            player.sendMessage(messages.render(text.linkCooldown(), Map.of("seconds", Long.toString(wait))));
            return;
        }

        int minutes = config.link().codeLifetimeMinutes();
        store.issue(player.getUniqueId(), player.getUsername(), minutes).whenComplete((code, error) -> {
            if (error != null) {
                cooldown.release(player.getUniqueId());
                logger.warn("/link failed for {} ({}): {}", player.getUsername(), player.getUniqueId(),
                        error.getCause() != null ? error.getCause().getMessage() : error.getMessage());
                player.sendMessage(messages.render(text.serviceUnavailable()));
                return;
            }
            logger.info("/link code issued to {} ({}), valid {} min", player.getUsername(),
                    player.getUniqueId(), minutes);
            player.sendMessage(messages.render(text.linkCode(),
                    Map.of("code", code, "minutes", Integer.toString(minutes))));
        });
    }
}
