package net.swzo.create_blueprinted.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.swzo.create_blueprinted.CB;
import net.swzo.create_blueprinted.util.CreateSchematicExporter;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SchematicRenderServer {
    private static final int PORT = 25514;
    private static final BlockingQueue<RenderTask> RENDER_QUEUE = new LinkedBlockingQueue<>();
    private static final int MAX_FILE_SIZE = 16 * 1024 * 1024;

    public static void start() {
        CB.LOGGER.info("[RenderServer] Initializing TCP Server on port {}...", PORT);
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(PORT)) {
                CB.LOGGER.info("[RenderServer] TCP Listener started successfully on port {}", PORT);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    String remoteAddr = clientSocket.getRemoteSocketAddress().toString();
                    CB.LOGGER.info("[RenderServer] New connection accepted from {}", remoteAddr);
                    handleConnection(clientSocket);
                }
            } catch (Exception e) {
                CB.LOGGER.error("[RenderServer] CRITICAL: TCP Listener encountered a fatal error", e);
            }
        }, "Schematic-TCP-Listener").start();

        new Thread(SchematicRenderServer::processQueue, "Schematic-Render-Worker").start();
        CB.LOGGER.info("[RenderServer] Worker thread spawned and waiting for tasks.");
    }

    private static void handleConnection(Socket socket) {
        String remoteAddr = socket.getRemoteSocketAddress().toString();
        try {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int orientationLen = in.readInt();
            byte[] orientationBytes = new byte[orientationLen];
            in.readFully(orientationBytes);
            String orientation = new String(orientationBytes, StandardCharsets.UTF_8);

            int quality = in.readInt();

            int nbtLen = in.readInt();
            CB.LOGGER.info("[RenderServer] Received request from {}: Orientation={}, Quality={}, NBT Size={} bytes",
                    remoteAddr, orientation, quality, nbtLen);

            if (nbtLen > MAX_FILE_SIZE) {
                CB.LOGGER.warn("[RenderServer] Rejected request from {}: File size {} exceeds limit", remoteAddr, nbtLen);
                sendError(socket, "Schematic exceeds 16MB limit.");
                return;
            }

            byte[] nbtBytes = new byte[nbtLen];
            in.readFully(nbtBytes);

            RENDER_QUEUE.put(new RenderTask(socket, orientation, quality, nbtBytes, System.currentTimeMillis()));
            CB.LOGGER.info("[RenderServer] Task added to queue. Current queue depth: {}", RENDER_QUEUE.size());

        } catch (Exception e) {
            CB.LOGGER.error("[RenderServer] Error reading data from " + remoteAddr, e);
            closeSocket(socket);
        }
    }

    private static void processQueue() {
        while (true) {
            RenderTask task = null;
            try {
                task = RENDER_QUEUE.take();
                long startTime = System.currentTimeMillis();
                long waitTime = startTime - task.queueTimestamp;

                CB.LOGGER.info("[Worker] Processing task (waited in queue for {}ms). Remaining in queue: {}",
                        waitTime, RENDER_QUEUE.size());

                ByteArrayInputStream bais = new ByteArrayInputStream(task.nbtBytes);
                CompoundTag tag = NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
                long nbtParsedTime = System.currentTimeMillis();

                CB.LOGGER.info("[Worker] Starting render: {}px, {} orientation", task.quality, task.orientation);
                var nativeImage = CreateSchematicExporter.renderSchematicImage(tag, task.quality, task.orientation).join();

                try (nativeImage) {
                    byte[] pngBytes = nativeImage.asByteArray();
                    DataOutputStream out = new DataOutputStream(task.socket.getOutputStream());
                    out.writeInt(pngBytes.length);
                    out.write(pngBytes);
                    out.flush();

                    long endTime = System.currentTimeMillis();
                    CB.LOGGER.info("[Worker] Task completed! Render Time: {}ms | Total Cycle: {}ms | Sent: {} bytes",
                            (endTime - nbtParsedTime), (endTime - startTime), pngBytes.length);
                }

            } catch (Exception e) {
                CB.LOGGER.error("[Worker] Failed to process render task", e);
                if (task != null) sendError(task.socket, "Render failed: " + e.getMessage());
            } finally {
                if (task != null) {
                    closeSocket(task.socket);
                }
            }
        }
    }

    private static void sendError(Socket socket, String message) {
        try {
            CB.LOGGER.error("[RenderServer] Sending error to client: {}", message);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(-1);
            byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
            out.writeInt(msgBytes.length);
            out.write(msgBytes);
            out.flush();
        } catch (Exception e) {
            CB.LOGGER.error("[RenderServer] Failed to send error message to client", e);
        }
        closeSocket(socket);
    }

    private static void closeSocket(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                String addr = socket.getRemoteSocketAddress().toString();
                socket.close();
                CB.LOGGER.info("[RenderServer] Connection closed: {}", addr);
            }
        } catch (Exception ignored) {}
    }

    private record RenderTask(Socket socket, String orientation, int quality, byte[] nbtBytes, long queueTimestamp) {}
}