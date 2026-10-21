package net.swzo.create_blueprinted.handler;

import com.mojang.blaze3d.platform.NativeImage;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.PngInfo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforge.common.NeoForge;
import net.swzo.create_blueprinted.CreateBlueprinted;
import net.swzo.create_blueprinted.api.RenderSchematicImageEvent;
import net.swzo.create_blueprinted.api.RenderSchematicImageEvent.Action;
import net.swzo.create_blueprinted.render.ImageActionProgress;
import net.swzo.create_blueprinted.render.SchematicRenderSettings;
import net.swzo.create_blueprinted.api.ShareProvider;
import net.swzo.create_blueprinted.api.ShareProviderRegistry;
import net.swzo.create_blueprinted.api.exception.EmptyImageBakeException;
import net.swzo.create_blueprinted.api.exception.EventCancelledException;
import net.swzo.create_blueprinted.api.exception.SchematicImageRenderException;
import net.swzo.create_blueprinted.render.SchematicImageRenderer;
import net.swzo.create_blueprinted.util.IOUtils;
import net.swzo.create_blueprinted.util.SchematicUtils;
import net.swzo.create_blueprinted.util.UIHelpers;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static net.swzo.create_blueprinted.CreateBlueprinted.*;
import static net.swzo.create_blueprinted.render.SchematicImageRenderer.downsample;
import static net.swzo.create_blueprinted.util.ThreadUtils.onClientThread;
import static net.swzo.create_blueprinted.util.ThreadUtils.onRenderThread;

public class SchematicImageHandler {

    public static final int RENDER_TIMEOUT_SECS = 60;

    private static final Component RENDER_ERROR = translatableError("schematic_render");
    private static final Component EXPORT_ERROR = translatableError("schematic_export");
    private static final Component RENDER_FAILED = translatableError("schematic_render.render_failed");
    private static final Component EMPTY_IMAGE_BAKE = translatableError("schematic_render.empty_image_bake");
    private static final Component CONVERT_AND_VALIDATE_FAILED = translatableError("schematic_render.convert_and_validate_failed");
    private static final Component TIMED_OUT = translatableError("schematic_render.timed_out", RENDER_TIMEOUT_SECS);
    private static final Component CLICK_TO_OPEN_EXPORT = translatable("command.renderschem.click_to_open");
    private static final Component CLICK_TO_OPEN_SHARE = translatable("command.shareschem.click_to_open");

    public static final ExecutorService PIPELINE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "create-blueprinted-image-handler");
        thread.setDaemon(true);
        return thread;
    });

    private final ResourceLocation handlerId;
    private final CommandSourceStack source;
    private final String schematicName;
    private final SchematicRenderSettings.Builder settingsBuilder;
    private final SchematicLevel schematicLevel;
    private final ShareProvider shareProvider;
    private String dataType = "image";

    private @Nullable Supplier<SchematicImageRenderer> renderSupplier;

    public SchematicImageHandler(String schematicName, CommandSourceStack source, SchematicRenderSettings.Builder settingsBuilder) {
        this(rl("default"), schematicName, source, settingsBuilder);
    }

    public SchematicImageHandler(ResourceLocation handlerId, String schematicName, CommandSourceStack source, SchematicRenderSettings.Builder settingsBuilder) {
        this (handlerId, schematicName, source, settingsBuilder, ShareProviderRegistry.getActiveShareProvider().orElse(null));
    }

    public SchematicImageHandler(ResourceLocation handlerId, String schematicName, CommandSourceStack source, SchematicRenderSettings.Builder settingsBuilder, @Nullable ShareProvider shareProvider) {
        this.handlerId = handlerId;
        this.source = source;
        this.schematicName = schematicName;
        this.settingsBuilder = settingsBuilder;
        this.shareProvider = shareProvider;
        if (shareProvider != null && shareProvider.includeSchematicData())
            this.dataType = "file";

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            throw new IllegalStateException("Cannot construct schematic image handler without a loaded level.");

        this.schematicLevel = new SchematicLevel(BlockPos.ZERO, level);
    }

    private void attachToSchematicName() {
        StructureTemplate template = SchematicUtils.loadTemplateFromSchematicName(schematicName);
        this.renderSupplier = () -> SchematicImageRenderer.bakeFromTemplate(template, schematicLevel)
                .orElseThrow(() -> new EmptyImageBakeException("Structure template is empty."));
    }

    public void attachToBlueprint(ItemStack blueprint) {
        StructureTemplate template = SchematicUtils.loadTemplateFromBlueprint(blueprint);
        this.renderSupplier = () -> SchematicImageRenderer.bakeFromTemplate(template, schematicLevel)
                .orElseThrow(() -> new EmptyImageBakeException("Structure template is empty."));
    }

    public void attachToBlockList(Map<BlockPos, StructureTemplate.StructureBlockInfo> blocks) {
        renderSupplier = () -> SchematicImageRenderer.bakeFromBlocks(blocks, schematicLevel)
                .orElseThrow(() -> new EmptyImageBakeException("List of structure template blocks is empty."));
    }

    public void export() {
        Minecraft client = Minecraft.getInstance();
        renderAndDownsample(Action.EXPORT)
                .thenApplyAsync(imageByteArray -> processExport(imageByteArray, client), PIPELINE)
                .whenComplete((file, e) -> onExportFinish(file, e, client));
    }

    public void share() {
        if (shareProvider == null) return;

        boolean useBlueprintsRenderer = shareProvider.beforeBake(handlerId, schematicName, settingsBuilder.build());
        if (!useBlueprintsRenderer) {
            LOGGER.info("Sharing an image of the schematic {}. Share provider {} implements a custom renderer.",
                    schematicName, shareProvider.id().toString());
            return;
        }
        Minecraft client = Minecraft.getInstance();
        renderAndDownsample(Action.SHARE)
                .whenCompleteAsync((imageByteArray, __) -> onShareFinish(imageByteArray, client), PIPELINE);
    }

    private CompletableFuture<byte[]> renderAndDownsample(Action action) {
        if (renderSupplier == null) attachToSchematicName();

        Minecraft client = Minecraft.getInstance();
        SchematicRenderSettings settings = settingsBuilder.build();
        int ssaa = settings.antialiasingFactor();

        if (shouldSendActionProgress(action)) ImageActionProgress.start(schematicName);

        CompletableFuture<SchematicImageRenderer> bakingFuture = isModLoaded("c2me") ?
                CompletableFuture.completedFuture(renderSupplier.get()) :
                CompletableFuture.supplyAsync(() -> renderSupplier.get(), PIPELINE);

        return bakingFuture.thenCompose(renderer -> firePreRenderEvent(renderer, action, client))
                .thenCompose(renderer -> onRenderThread(() -> render(renderer, action)))
                .thenApplyAsync(image -> ssaa == 1 ? image : downsample(image, ssaa), PIPELINE)
                .thenApply(this::convertToByteArray)
                .thenCompose(imageByteArray -> firePostRenderEvent(imageByteArray, action, client))
                .orTimeout(RENDER_TIMEOUT_SECS, TimeUnit.SECONDS)
                .handle((imageByteArray, e) -> handleRenderExceptions(imageByteArray, e, action, client));
    }

    private NativeImage render(SchematicImageRenderer renderer, Action action) {
        if (shouldSendActionProgress(action)) ImageActionProgress.setState(ImageActionProgress.RENDERING);
        return renderer.render(settingsBuilder.build());
    }

    private File processExport(byte[] imageByteArray, Minecraft client) {
        if (imageByteArray == null) return null;
        String gameDirectory = client.gameDirectory.getPath();
        File schematicDirectory = new File(gameDirectory, "schematics");

        ImageActionProgress.setState(ImageActionProgress.EXPORTING);
        try {
            return IOUtils.saveImage(schematicDirectory, schematicName, "png", imageByteArray);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private void onShareFinish(@Nullable byte[] imageByteArray, Minecraft client) {
        if (imageByteArray == null) return;

        boolean sendActionProgress = shouldSendActionProgress(Action.SHARE);
        if (sendActionProgress) ImageActionProgress.setState(ImageActionProgress.SHARING);

        Future<URL> shareUrlFuture = shareProvider.onRender(handlerId, schematicName, settingsBuilder.build(), imageByteArray);
        Throwable shareError = null;
        String errorLogMessage = null;

        try {
            URL url = shareUrlFuture.get(shareProvider.timeout(), TimeUnit.SECONDS);
            if (url != null) {
                if (sendActionProgress) client.execute(() -> {
                    ImageActionProgress.setState(ImageActionProgress.SHARED);
                    String urlString = url.toString();
                    Component finalMessage = translatable("command.shareschem.success")
                            .withColor(UIHelpers.LIGHT_GREEN_TEXT_COLOR)
                            .append(Component.literal(urlString)
                                    .withStyle(Style.EMPTY
                                            .withColor(UIHelpers.DARK_GREEN_TEXT_COLOR)
                                            .withUnderlined(true)
                                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, urlString))
                                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, CLICK_TO_OPEN_SHARE))));
                    source.sendSuccess(() -> finalMessage, false);
                });
                LOGGER.info("Schematic {} {} sent to: {}. {}", dataType, schematicName, url, getHandlerContext(handlerId));
            } else
                errorLogMessage = "";
        } catch (InterruptedException | ExecutionException e) {
            errorLogMessage = "Task was either interrupted or failed to execute.";
            shareError = e;
        } catch (TimeoutException e) {
            errorLogMessage = "Operation timed out after " + shareProvider.timeout()  + " seconds.";
        }

        if (errorLogMessage != null) {
            if (sendActionProgress) client.execute(() -> ImageActionProgress.setState(ImageActionProgress.SHARE_FAILED));
            if (shareError != null)
                LOGGER.error("Failed to share schematic {} {}. {} {}", dataType, schematicName, errorLogMessage,
                        getHandlerContext(handlerId), shareError);
            else
                LOGGER.error("Failed to share schematic {} {}. {} {}", dataType, schematicName, errorLogMessage,
                        getHandlerContext(handlerId));
        }
    }

    private void onExportFinish(@Nullable File outputFile, @Nullable Throwable e, Minecraft client) {
        if (outputFile == null) return; // Event canceled or rendering failed
        Throwable cause = getExceptionCause(e);

        if (cause != null) {
            if (cause instanceof IOException) client.execute(() -> source.sendFailure(EXPORT_ERROR));
            ImageActionProgress.setState(ImageActionProgress.EXPORT_FAILED);
            LOGGER.error("Failed to export schematic image {}. {}", schematicName, getHandlerContext(handlerId), e);
            return;
        }
        client.execute(() -> {
            Component finalMessage = translatable("command.renderschem.success")
                    .withColor(UIHelpers.LIGHT_BLUE_TEXT_COLOR)
                    .append(Component.literal(outputFile.toString())
                            .withStyle(Style.EMPTY
                                    .withColor(UIHelpers.DARK_BLUE_TEXT_COLOR)
                                    .withUnderlined(true)
                                    .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, outputFile.getAbsolutePath()))
                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, CLICK_TO_OPEN_EXPORT))));
            source.sendSuccess(() -> finalMessage, false);
        });
        ImageActionProgress.setState(ImageActionProgress.EXPORTED);
    }

    private byte[] convertToByteArray(NativeImage image) {
        byte [] imageByteArray;
        try (image) {
            imageByteArray = image.asByteArray();
            PngInfo.fromBytes(imageByteArray);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
        return imageByteArray;
    }

    private CompletableFuture<SchematicImageRenderer> firePreRenderEvent(SchematicImageRenderer renderer, Action action, Minecraft client) {
        return onClientThread(() -> {
            SchematicLevel schematicLevel = renderer.getSchematicLevel();
            var renderEvent = new RenderSchematicImageEvent.Pre(handlerId, schematicName, settingsBuilder, action, schematicLevel);

            NeoForge.EVENT_BUS.post(renderEvent);
            if (renderEvent.isCanceled()) throw new EventCancelledException();
            return renderer;
        }, client);
    }

    private CompletableFuture<byte[]> firePostRenderEvent(byte[] imageByteArray, Action action, Minecraft client) {
        return onClientThread(() -> {
            var renderEvent = new RenderSchematicImageEvent.Post(handlerId, schematicName, settingsBuilder, action, imageByteArray);

            NeoForge.EVENT_BUS.post(renderEvent);
            if (renderEvent.isCanceled()) throw new EventCancelledException();
            return imageByteArray;
        }, client);
    }

    private byte[] handleRenderExceptions(@Nullable byte[] imageByteArray, @Nullable Throwable e, Action action, Minecraft client) {
        Throwable cause = getExceptionCause(e);
        if (cause == null) return imageByteArray;
        if (cause instanceof EventCancelledException) {
            ImageActionProgress.cancel();
            return null;
        }
        if (shouldSendActionProgress(action)) ImageActionProgress.setState(ImageActionProgress.RENDER_FAILED);
        client.execute(() -> {
            MutableComponent renderError = RENDER_ERROR.copy().append(" ");

            //noinspection IfCanBeSwitch
            if (cause instanceof EmptyImageBakeException)
                source.sendFailure(renderError.append(EMPTY_IMAGE_BAKE));
            else if (cause instanceof SchematicImageRenderException)
                source.sendFailure(renderError.append(RENDER_FAILED));
            else if (cause instanceof IOException)
                source.sendFailure(renderError.append(CONVERT_AND_VALIDATE_FAILED));
            else if (cause instanceof TimeoutException)
                source.sendFailure(renderError.append(TIMED_OUT));

            if (action == Action.SHARE)
                shareProvider.onRenderFailure(handlerId, schematicName, settingsBuilder.build(), cause, renderError);
        });
        CreateBlueprinted.LOGGER.error("Failed to render schematic {}. {}", schematicName, getHandlerContext(handlerId), e);
        return null;
    }

    private String getHandlerContext(ResourceLocation handlerId) {
        return "(handler: " + handlerId.toString() + ", share provider: " + (shareProvider != null ? shareProvider.id().toString() : "N/A") + ").";
    }

    private boolean shouldSendActionProgress(Action action) {
        return action == Action.EXPORT || shareProvider == null || !shareProvider.silenceMessages();
    }

    private static Throwable getExceptionCause(@Nullable Throwable e) {
        return (e instanceof CompletionException && e.getCause() != null) ? e.getCause() : e;
    }
}
