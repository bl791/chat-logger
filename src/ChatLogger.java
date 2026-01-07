package com.mclogger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

public class ChatLogger {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter LOG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String currentServerName = null;
    private static String sessionId = null;
    private static Path sessionLogFile = null;
    private static JsonArray chatMessages = null;
    private static long sessionStartTime = 0;

    public static void initialize() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onChatMessage(message);
            }
        });

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String senderName = null;
            UUID senderId = null;
            if (sender != null) {
                // Try record-style accessors first (1.21.10+), then legacy getters
                try {
                    senderName = (String) sender.getClass().getMethod("name").invoke(sender);
                } catch (Exception e) {
                    try {
                        senderName = (String) sender.getClass().getMethod("getName").invoke(sender);
                    } catch (Exception e2) {
                        // Fallback
                    }
                }
                try {
                    senderId = (UUID) sender.getClass().getMethod("id").invoke(sender);
                } catch (Exception e) {
                    try {
                        senderId = (UUID) sender.getClass().getMethod("getId").invoke(sender);
                    } catch (Exception e2) {
                        // Fallback
                    }
                }
            }
            onPublicChatMessage(message, senderId, senderName);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            onDisconnect();
        });

        MCLogger.LOGGER.info("Chat message listener registered");
    }

    private static void onChatMessage(Text message) {
        // This captures game messages (non-chat), we skip these
    }

    private static void onPublicChatMessage(Text message, UUID senderId, String senderName) {
        MinecraftClient client = MinecraftClient.getInstance();

        if (client.getCurrentServerEntry() == null && client.isIntegratedServerRunning()) {
            // Single player world, use world name
            String worldName = client.getServer() != null ?
                client.getServer().getSaveProperties().getLevelName() : "singleplayer";
            ensureSession(sanitizeFileName(worldName));
        } else if (client.getCurrentServerEntry() != null) {
            // Multiplayer server
            String serverAddress = client.getCurrentServerEntry().address;
            ensureSession(sanitizeFileName(serverAddress));
        } else {
            return; // Not connected to anything
        }

        logMessage(message.getString(), senderId, senderName);
    }

    private static void ensureSession(String serverName) {
        if (currentServerName == null || !currentServerName.equals(serverName)) {
            // New server or first connection
            startNewSession(serverName);
        }
    }

    private static void startNewSession(String serverName) {
        // Save previous session if exists
        saveCurrentSession();

        currentServerName = serverName;
        sessionId = UUID.randomUUID().toString().substring(0, 8);
        sessionStartTime = System.currentTimeMillis();
        chatMessages = new JsonArray();

        LocalDateTime now = LocalDateTime.now();
        String fileName = "session_" + now.format(FILE_DATE_FORMAT) + "_" + sessionId + ".json";

        Path logsDir = getLogsDirectory().resolve(serverName);
        try {
            Files.createDirectories(logsDir);
            sessionLogFile = logsDir.resolve(fileName);
            MCLogger.LOGGER.info("Started new chat logging session: " + sessionLogFile);
        } catch (IOException e) {
            MCLogger.LOGGER.error("Failed to create logs directory", e);
        }
    }

    private static void logMessage(String message, UUID senderId, String senderName) {
        if (chatMessages == null || sessionLogFile == null) {
            return;
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("timestamp", LocalDateTime.now().format(LOG_DATE_FORMAT));
        entry.addProperty("epochMillis", System.currentTimeMillis());
        entry.addProperty("senderUuid", senderId != null ? senderId.toString() : "unknown");
        entry.addProperty("senderName", senderName != null ? senderName : "unknown");
        entry.addProperty("message", message);

        chatMessages.add(entry);

        // Save after each message to prevent data loss
        saveCurrentSession();
    }

    public static void saveCurrentSession() {
        if (chatMessages == null || sessionLogFile == null || chatMessages.isEmpty()) {
            return;
        }

        JsonObject sessionData = new JsonObject();
        sessionData.addProperty("server", currentServerName);
        sessionData.addProperty("sessionId", sessionId);
        sessionData.addProperty("sessionStart", Instant.ofEpochMilli(sessionStartTime)
            .atZone(ZoneId.systemDefault())
            .format(LOG_DATE_FORMAT));
        sessionData.addProperty("lastUpdated", LocalDateTime.now().format(LOG_DATE_FORMAT));
        sessionData.addProperty("messageCount", chatMessages.size());
        sessionData.add("messages", chatMessages);

        try (FileWriter writer = new FileWriter(sessionLogFile.toFile())) {
            GSON.toJson(sessionData, writer);
        } catch (IOException e) {
            MCLogger.LOGGER.error("Failed to save chat log", e);
        }
    }

    public static void onDisconnect() {
        saveCurrentSession();
        currentServerName = null;
        sessionId = null;
        sessionLogFile = null;
        chatMessages = null;
        sessionStartTime = 0;
    }

    private static Path getLogsDirectory() {
        return MinecraftClient.getInstance().runDirectory.toPath().resolve("chatlogs");
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^a-zA-Z0-9.-]", "_")
                   .replaceAll("_+", "_")
                   .toLowerCase();
    }
}
