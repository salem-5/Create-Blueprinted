package net.swzo.create_blueprinted.util;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.swzo.create_blueprinted.CB;
import net.swzo.create_blueprinted.render.RenderProgress;
import net.swzo.create_blueprinted.render.SchematicRenderHandle;
import net.swzo.create_blueprinted.render.SchematicRenderSettings;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CreateSchematicExporter {

    public interface ProgressListener extends Consumer<RenderProgress.Stage> {
        ProgressListener NOOP = stage -> {};
    }

    public static CompletableFuture<NativeImage> renderSchematicImage(CompoundTag tag,
                                                                      SchematicRenderSettings settings,
                                                                      ProgressListener progress) {
        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;

        int ssaa = settings.antialiasing();
        SchematicRenderSettings renderSettings = ssaa == 1 ? settings : settings.withImageWidth(settings.imageWidth() * ssaa);

        return CompletableFuture.supplyAsync(() -> {
                    listener.accept(RenderProgress.Stage.BUILDING);
                    return SchematicRenderHandle.bake(tag);
                })
                .exceptionallyCompose(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof IllegalStateException) {
                        return CompletableFuture.failedFuture(cause);
                    }
                    CB.LOGGER.warn("Off-thread schematic bake failed; retrying on the render thread.", cause);
                    return onRenderThread(() -> SchematicRenderHandle.bake(tag));
                })
                .thenCompose(handle -> {
                    listener.accept(RenderProgress.Stage.RENDERING);
                    return onRenderThread(() -> {
                        try {
                            return handle.renderAndDownload(renderSettings);
                        } finally {
                            handle.close();
                        }
                    });
                })
                .thenApplyAsync(image -> ssaa == 1 ? image : downsample(image, ssaa));
    }

    private static NativeImage downsample(NativeImage source, int factor) {
        try (source) {
            int outW = Math.max(1, source.getWidth() / factor);
            int outH = Math.max(1, source.getHeight() / factor);
            NativeImage out = new NativeImage(outW, outH, false);
            int samples = factor * factor;

            for (int y = 0; y < outH; y++) {
                for (int x = 0; x < outW; x++) {
                    long aSum = 0, c0 = 0, c1 = 0, c2 = 0;
                    for (int dy = 0; dy < factor; dy++) {
                        for (int dx = 0; dx < factor; dx++) {
                            int p = source.getPixelRGBA(x * factor + dx, y * factor + dy);
                            int a = (p >>> 24) & 0xFF;
                            aSum += a;
                            c0 += (long) (p & 0xFF) * a;
                            c1 += (long) ((p >> 8) & 0xFF) * a;
                            c2 += (long) ((p >> 16) & 0xFF) * a;
                        }
                    }
                    int a = (int) (aSum / samples);
                    int r0 = aSum == 0 ? 0 : (int) (c0 / aSum);
                    int r1 = aSum == 0 ? 0 : (int) (c1 / aSum);
                    int r2 = aSum == 0 ? 0 : (int) (c2 / aSum);
                    out.setPixelRGBA(x, y, (a << 24) | (r2 << 16) | (r1 << 8) | r0);
                }
            }
            return out;
        }
    }

    public static CompletableFuture<NativeImage> renderSchematicImage(CompoundTag tag, int pixelWidthPerBlock, String orientation) {
        return renderSchematicImage(tag, SchematicRenderSettings.fromOrientation(orientation, pixelWidthPerBlock), ProgressListener.NOOP);
    }

    public static void export(CommandSourceStack source, String filename, SchematicRenderSettings settings) {
        Minecraft mc = Minecraft.getInstance();
        Path schematicPath = mc.gameDirectory.toPath().resolve("schematics").resolve(filename + ".nbt");

        if (!Files.exists(schematicPath)) {
            source.sendFailure(Component.translatable("create_blueprinted.command.renderschem.not_found", filename)
                    .withStyle(s -> s.withColor(UiHelpers.ERROR_PRIMARY)));
            return;
        }

        source.sendSuccess(() -> Component.translatable("create_blueprinted.command.renderschem.starting", filename)
                .withStyle(s -> s.withColor(UiHelpers.LIGHT_BLUE_TEXT_COLOR)), false);

        RenderProgress.start(filename);
        ProgressListener progress = stage -> RenderProgress.stage(stage, filename);

        CompletableFuture.supplyAsync(() -> {
                    progress.accept(RenderProgress.Stage.PARSING);
                    try (InputStream inputStream = Files.newInputStream(schematicPath)) {
                        return NbtIo.readCompressed(inputStream);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read NBT file.", e);
                    }
                })
                .thenCompose(tag -> renderSchematicImage(tag, settings, progress))
                .thenAcceptAsync(image -> {
                    progress.accept(RenderProgress.Stage.SAVING);
                    try (image) {
                        File outputFile = new File(mc.gameDirectory, "schematics/" + filename + ".png");
                        image.writeToFile(outputFile);

                        mc.execute(() -> {
                            RenderProgress.success(filename);
                            Component successMsg = Component.translatable("create_blueprinted.command.renderschem.success")
                                    .withStyle(s -> s.withColor(UiHelpers.LIGHT_BLUE_TEXT_COLOR))
                                    .append(Component.literal(outputFile.getName())
                                            .withStyle(Style.EMPTY
                                                    .withColor(UiHelpers.DARK_BLUE_TEXT_COLOR)
                                                    .withUnderlined(true)
                                                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, outputFile.getAbsolutePath()))
                                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("create_blueprinted.command.renderschem.click_to_open")))));
                            source.sendSuccess(() -> successMsg, false);
                        });
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to save rendered image.", e);
                    }
                }).orTimeout(2, TimeUnit.MINUTES).exceptionally(ex -> {
                    mc.execute(() -> {
                        RenderProgress.fail(filename);
                        if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
                            source.sendFailure(Component.translatable("create_blueprinted.command.renderschem.timeout")
                                    .withStyle(s -> s.withColor(UiHelpers.ERROR_PRIMARY)));
                        } else {
                            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                            source.sendFailure(Component.translatable("create_blueprinted.command.renderschem.error", cause.getMessage())
                                    .withStyle(s -> s.withColor(UiHelpers.ERROR_PRIMARY)));
                            CB.LOGGER.error("Exception Thrown: ", ex);
                        }
                    });
                    return null;
                });
    }

    public static void export(CommandSourceStack source, String filename, String orientation, int pixelWidthPerBlock) {
        export(source, filename, SchematicRenderSettings.fromOrientation(orientation, pixelWidthPerBlock));
    }

    private static <T> CompletableFuture<T> onRenderThread(Supplier<T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        RenderSystem.recordRenderCall(() -> {
            try {
                future.complete(work.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
