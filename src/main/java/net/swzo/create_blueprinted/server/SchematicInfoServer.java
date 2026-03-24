package net.swzo.create_blueprinted.server;

import com.google.gson.JsonObject;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.swzo.create_blueprinted.CB;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import com.simibubi.create.content.schematics.requirement.ItemRequirement;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.item.ItemStack;

public class SchematicInfoServer {
    private static final int PORT = 25515;
    private static final BlockingQueue<InfoTask> INFO_QUEUE = new LinkedBlockingQueue<>();
    private static final int MAX_FILE_SIZE = 16 * 1024 * 1024;

    public static void start() {
        CB.LOGGER.info("[InfoServer] Initializing TCP Server on port {}...", PORT);
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                CB.LOGGER.info("[InfoServer] TCP Listener started successfully on port {}", PORT);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    String remoteAddr = clientSocket.getRemoteSocketAddress().toString();
                    CB.LOGGER.info("[InfoServer] New connection accepted from {}", remoteAddr);
                    handleConnection(clientSocket);
                }
            } catch (Exception e) {
                CB.LOGGER.error("[InfoServer] CRITICAL: TCP Listener encountered a fatal error", e);
            }
        }, "Schematic-Info-Listener").start();
        new Thread(SchematicInfoServer::processQueue, "Schematic-Info-Worker").start();
        CB.LOGGER.info("[InfoServer] Worker thread spawned and waiting for tasks.");
    }

    private static void handleConnection(Socket socket) {
        String remoteAddr = socket.getRemoteSocketAddress().toString();
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int nbtLen = in.readInt();
            CB.LOGGER.info("[InfoServer] Received request from {}: NBT Size={} bytes", remoteAddr, nbtLen);

            if (nbtLen > MAX_FILE_SIZE || nbtLen <= 0) {
                CB.LOGGER.warn("[InfoServer] Rejected request from {}: File size {} invalid or exceeds limit", remoteAddr, nbtLen);
                sendError(socket, "Schematic exceeds 16MB limit or is invalid.");
                return;
            }

            byte[] nbtBytes = new byte[nbtLen];
            in.readFully(nbtBytes);

            INFO_QUEUE.put(new InfoTask(socket, nbtBytes, System.currentTimeMillis()));
            CB.LOGGER.info("[InfoServer] Task added to queue. Current queue depth: {}", INFO_QUEUE.size());

        } catch (Exception e) {
            CB.LOGGER.error("[InfoServer] Error reading data from " + remoteAddr, e);
            closeSocket(socket);
        }
    }

    private static void processQueue() {
        Minecraft mc = Minecraft.getInstance();
        while (true) {
            InfoTask task = null;
            try {
                task = INFO_QUEUE.take();
                long startTime = System.currentTimeMillis();

                ByteArrayInputStream bais = new ByteArrayInputStream(task.nbtBytes);
                CompoundTag tag = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());

                CompletableFuture<String> processFuture = new CompletableFuture<>();
                mc.execute(() -> {
                    try {
                        if (mc.level == null) {
                            processFuture.completeExceptionally(new IllegalStateException("World is not loaded yet."));
                            return;
                        }

                        StructureTemplate template = new StructureTemplate();
                        template.load(Objects.requireNonNull(mc.level).holderLookup(Registries.BLOCK), tag);

                        Vec3i size = template.getSize();
                        SchematicLevel schematicLevel = new SchematicLevel(BlockPos.ZERO, mc.level);
                        StructurePlaceSettings settings = new StructurePlaceSettings();
                        template.placeInWorld(schematicLevel, BlockPos.ZERO, BlockPos.ZERO, settings, mc.level.random, Block.UPDATE_CLIENTS);

                        int totalBlocks = 0;
                        Map<String, Integer> materials = new HashMap<>();

                        for (int x = 0; x < size.getX(); x++) {
                            for (int y = 0; y < size.getY(); y++) {
                                for (int z = 0; z < size.getZ(); z++) {
                                    BlockPos pos = new BlockPos(x, y, z);
                                    BlockState state = schematicLevel.getBlockState(pos);

                                    if (!state.isAir()) {
                                        totalBlocks++;
                                        BlockEntity be = schematicLevel.getBlockEntity(pos);
                                        ItemRequirement requirement = ItemRequirement.of(state, be);
                                        if (!requirement.isEmpty() && !requirement.isInvalid()) {
                                            for (ItemRequirement.StackRequirement stackReq : requirement.getRequiredItems()) {
                                                if (stackReq.usage == ItemRequirement.ItemUseType.CONSUME ||
                                                        stackReq.usage == ItemRequirement.ItemUseType.DAMAGE) {

                                                    ItemStack stack = stackReq.stack;
                                                    if (stack.isEmpty()) continue;
                                                    String itemName = stack.getHoverName().getString();
                                                    int count = stack.getCount();

                                                    materials.put(itemName, materials.getOrDefault(itemName, 0) + count);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        JsonObject response = new JsonObject();

                        JsonObject dims = new JsonObject();
                        dims.addProperty("x", size.getX());
                        dims.addProperty("y", size.getY());
                        dims.addProperty("z", size.getZ());
                        response.add("dimensions", dims);

                        response.addProperty("totalBlocks", totalBlocks);
                        response.addProperty("uniqueBlocks", materials.size());

                        JsonObject mats = new JsonObject();
                        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
                            mats.addProperty(entry.getKey(), entry.getValue());
                        }
                        response.add("materials", mats);

                        processFuture.complete(response.toString());
                    } catch (Exception e) {
                        processFuture.completeExceptionally(e);
                    }
                });

                String jsonResponse = processFuture.join();
                byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);

                DataOutputStream out = new DataOutputStream(task.socket.getOutputStream());
                out.writeInt(responseBytes.length);
                out.write(responseBytes);
                out.flush();

                long endTime = System.currentTimeMillis();
                CB.LOGGER.info("[InfoWorker] Task completed in {}ms | Sent: {} bytes", (endTime - startTime), responseBytes.length);

            } catch (Exception e) {
                CB.LOGGER.error("[InfoWorker] Failed to process info task", e);
                if (task != null) sendError(task.socket, "Info extraction failed: " + e.getMessage());
            } finally {
                if (task != null) {
                    closeSocket(task.socket);
                }
            }
        }
    }

    private static void sendError(Socket socket, String message) {
        try {
            CB.LOGGER.error("[InfoServer] Sending error to client: {}", message);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(-1);
            byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
            out.writeInt(msgBytes.length);
            out.write(msgBytes);
            out.flush();
        } catch (Exception e) {
            CB.LOGGER.error("[InfoServer] Failed to send error message to client", e);
        }
        closeSocket(socket);
    }

    private static void closeSocket(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (Exception ignored) {}
    }

    private record InfoTask(Socket socket, byte[] nbtBytes, long queueTimestamp) {}
}