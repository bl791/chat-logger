package com.mclogger;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCLogger implements ClientModInitializer {
    public static final String MOD_ID = "mclogger";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("MCLogger initialized - Chat messages will be logged to JSON files");
        ChatLogger.initialize();
    }
}
