package net.swzo.create_blueprinted.api;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.swzo.create_blueprinted.render.SchematicRenderSettings;
import org.jetbrains.annotations.Nullable;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * <p>Share providers are used as a common interface for schematic file & image sharing.
 * Blueprinted supports multiple share providers so players can switch between them in-game.</p>
 *
 * <p>Share providers are registered within {@link net.swzo.create_blueprinted.api.ShareProviderRegistry}.
 * You don't need to register a share provider, but it will allow users to see it in the schematic table GUI.
 * It will also allow players to switch between providers.</p>
 *
 * <p>If you want to provide an implementation that sends an image to a remote server you must include
 * some form of serverside image sanitization. Blueprinted won't tell the player where the file was sent.
 * It is your responsibility to inform the player.</p>.
 *
 * <p>You can use the image renderer provided by Blueprinted (I recommend this) or implement your own.</p>
 */
public interface ShareProvider {

    int MAX_URL_CHAR_LENGTH = 80;

    /**
     * Unique ID for the provider.
     *
     * @return Provider ID resource location
     */
    ResourceLocation id();

    /**
     * Describes where the file will be sent. For example: "Brassworks SMP Discord Server".
     *
     * @return A text component representing the schematic image files destination
     */
    Component destinationName();

    /**
     * URL representing where the file will be sent.
     *
     * @return Destination URL. Must use the HTTPS protocol and be shorter than {@link #MAX_URL_CHAR_LENGTH}
     *         characters long.
     */
    String destinationUrl();

    /**
     * Called before the schematic is baked (Block data converted into a SchematicLevel).
     *
     * <p>If you want blueprinted to render the schematic return true. The result will be sent to
     * {@link ShareProvider#onRender(ResourceLocation, String, SchematicRenderSettings, byte[])}.</p>
     *
     * <p>If you want to implement a custom renderer return false. Make sure to notify the player on
     * where the file was sent</p>
     *
     * @param handlerId The image handlers ID (Default ID = create_blueprinted:default)
     * @param schematicName Name of the schematic
     * @param renderSettings Settings used to render the schematic
     * @return False if a custom renderer should be used or true if the result should be handled by Blueprinted.
     */
    default boolean beforeBake(ResourceLocation handlerId, String schematicName, SchematicRenderSettings renderSettings) { return true; }

    /**
     * Determine what happens to the schematic image after it's rendered by Blueprinted. Called on an async thread.
     *
     * @param handlerId The image handlers ID (Default ID = create_blueprinted:default)
     * @param schematicName Name of the schematic
     * @param renderSettings Settings used to render the schematic
     * @param imageByteArray The rendered PNG schematic image in a byte array format
     * @return A future which holds a reference to a URL representing the location the image has been shared or null if the operation failed.
     *         You can also return null if you implemented your own custom renderer. In which case this method won't be called.
     *         The futures deadline is determined by {@link ShareProvider#timeout()} and will cancel automatically if no value
     *         is returned within this timeframe.
     */
    default Future<@Nullable URL> onRender(ResourceLocation handlerId, String schematicName, SchematicRenderSettings renderSettings, byte[] imageByteArray) { return CompletableFuture.completedFuture(null); }

    /**
     * <p>Fires if schematic image rendering fails. Called on the main client thread.</p>
     * Expected exception types:
     * <ul>
     *     <li>{@code EmptyImageBakeException} - Failed to populate a schematic level with content</li>
     *     <li>{@code SchematicImageRenderException} - Image failed to render</li>
     *     <li>{@code IOException} - Image conversion or validation failed</li>
     *     <li>{@code TimeoutException} - Rendering timed out after
     *              {@link net.swzo.create_blueprinted.handler.SchematicImageHandler#RENDER_TIMEOUT_SECS RENDER_TIMEOUT_SECS}</li>
     * </ul>
     *
     * @param handlerId The image handlers ID (Default ID = create_blueprinted:default)
     * @param schematicName Name of the schematic
     * @param renderSettings Settings used to render the schematic
     * @param error The exception thrown after rendering failed and the associated error message
     * @param errorMessage Associated error message
     */
    default void onRenderFailure(ResourceLocation handlerId, String schematicName, SchematicRenderSettings renderSettings, Throwable error, Component errorMessage) {}

    /**
     * Additional information about the share provider. Displayed in the tooltip below the destination name and url.
     * Also shown before a file is shared using the <code>/schematic share</code> command.
     *
     * @return Text components representing extra information tied to the share provider. Each element represents a newline.
     */
    default List<Component> extras() { return List.of(); }

    /**
     * Whether to hide the SHIFT & CTRL hints displayed in the schematic table tooltip.
     *
     * @return If true disable hints, otherwise include them.
     */
    default boolean hideTooltipHints() { return false; }

    /**
     * Is schematic data sent alongside a schematic image. This could include data about block types and
     * positions, block entity data, etc...
     */
    default boolean includeSchematicData() { return false; }

    /**
     * Defines if status messages sent to the player should be silenced.
     */
    default boolean silenceMessages() { return false; }

    /**
     * Number of seconds before {@link ShareProvider#onRender(ResourceLocation, String, SchematicRenderSettings, byte[])}
     * stops waiting for a result. This value is ignored if you use a custom renderer.
     *
     * @return Share timeout in seconds
     */
    default int timeout() { return 30; }

    /**
     * Gets the priority of the current provider. The highest priority provider is shown to the user
     * the first time Create: Configured is loaded. Then it is manually set by the user.
     *
     * @return Priority of the current provider (Default value = 1)
     */
    default int priority() { return 1; }
}
