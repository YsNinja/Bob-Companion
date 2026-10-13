package com.quackcraft.quackingly;

import com.quackcraft.quackingly.command.QuackSkinCommand;
import com.quackcraft.quackingly.companion.ChatInterceptor;
import com.quackcraft.quackingly.companion.CompanionManager;
import com.quackcraft.quackingly.companion.CompanionBrain;
import com.quackcraft.quackingly.config.QuackinglyConfig;
import com.quackcraft.quackingly.network.QuackinglyPayloads;
import com.quackcraft.quackingly.network.ServerCompanionPackets;
import com.quackcraft.quackingly.voice.SilenceWatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Quackingly implements ModInitializer {
    public static final String MOD_ID = "quackingly";
    public static final Logger LOGGER = LoggerFactory.getLogger("Quackingly");

    @Override
    public void onInitialize() {
        QuackinglyConfig.load();
        String ver = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        LOGGER.info("[Quackingly] Initialising v{}. Default provider hint: {}",
                ver, QuackinglyConfig.get().detectedProvider);

        QuackinglyPayloads.register();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            CompanionManager.getInstance().onServerStarted(server);
            SilenceWatcher.start(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SilenceWatcher.stop();
            CompanionManager.getInstance().onServerStopped();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                QuackSkinCommand.register(dispatcher));

        // Divert chat before Minecraft broadcasts it. CHAT_MESSAGE is post-broadcast.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String text;
            try {
                text = message.getContent().getString();
            } catch (Throwable t) {
                try { text = message.getSignedContent(); }
                catch (Throwable t2) { return true; }
            }
            if (text == null || text.startsWith("/")) return true;
            return !ChatInterceptor.intercept(sender.getServer(), sender, text);
        });

        ServerCompanionPackets.register();
        CompanionBrain.registerTickHook();
    }
}

