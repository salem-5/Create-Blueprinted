package net.swzo.create_blueprinted.mixin;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.table.SchematicTableScreen;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.swzo.create_blueprinted.util.CreateSchematicExporter;
import net.swzo.create_blueprinted.util.UiHelpers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(SchematicTableScreen.class)
public abstract class SchematicTableScreenMixin extends Screen {
    @Shadow private ScrollInput schematicsArea;
    @Shadow private IconButton refreshButton;
    @Shadow private List<Rect2i> extraAreas;
    @Shadow private Label schematicsLabel;

    @Unique private boolean brassworks$shiftWasDownOnInit = false;
    @Unique private boolean brassworks$ctrlWasDownOnInit = false;

    protected SchematicTableScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = "init", at = @At("HEAD"))
    private void onInitHead(CallbackInfo ci) {
        brassworks$shiftWasDownOnInit = Screen.hasShiftDown();
        brassworks$ctrlWasDownOnInit = Screen.hasControlDown();
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInitTail(CallbackInfo ci) {
        int renderButtonX = refreshButton.getX();
        int renderButtonY = refreshButton.getY() - refreshButton.getHeight() - 4;

        IconButton renderButton = new IconButton(renderButtonX, renderButtonY, AllIcons.I_CONFIG_SAVE) {
            @Override
            public void doRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {

                if (!Screen.hasShiftDown()) SchematicTableScreenMixin.this.brassworks$shiftWasDownOnInit = false;
                if (!Screen.hasControlDown()) SchematicTableScreenMixin.this.brassworks$ctrlWasDownOnInit = false;

                boolean shiftActive = Screen.hasShiftDown() && !SchematicTableScreenMixin.this.brassworks$shiftWasDownOnInit;
                boolean ctrlActive = Screen.hasControlDown() && !SchematicTableScreenMixin.this.brassworks$ctrlWasDownOnInit;

                this.toolTip.clear();
                this.toolTip.add(Component.translatable("create_blueprinted.gui.schematic_table.render_button.title").withColor(UiHelpers.DARK_BLUE_TEXT_COLOR));

                var resComponent = Component.translatable("create_blueprinted.gui.schematic_table.render_button.res").withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(shiftActive ? "1024" : "256").withColor(UiHelpers.LIGHT_BLUE_TEXT_COLOR));

                if (!shiftActive) {
                    resComponent.append(Component.translatable("create_blueprinted.gui.schematic_table.render_button.res.hint",
                            Component.literal("Shift").withStyle(ChatFormatting.GRAY)
                    ).withStyle(ChatFormatting.DARK_GRAY));
                }
                this.toolTip.add(resComponent);

                var dirComponent = Component.translatable("create_blueprinted.gui.schematic_table.render_button.dir").withStyle(ChatFormatting.GRAY)
                        .append(Component.translatable(ctrlActive ? "create_blueprinted.gui.schematic_table.render_button.dir.left" : "create_blueprinted.gui.schematic_table.render_button.dir.right").withColor(UiHelpers.LIGHT_BLUE_TEXT_COLOR));

                if (!ctrlActive) {
                    dirComponent.append(Component.translatable("create_blueprinted.gui.schematic_table.render_button.dir.hint",
                            Component.literal("Ctrl").withStyle(ChatFormatting.GRAY)
                    ).withStyle(ChatFormatting.DARK_GRAY));
                }
                this.toolTip.add(dirComponent);

                this.toolTip.add(Component.literal(" "));
                this.toolTip.add(Component.translatable("create_blueprinted.gui.schematic_table.render_button.save_hint").withStyle(ChatFormatting.GRAY));
                this.toolTip.add(Component.translatable("create_blueprinted.gui.schematic_table.render_button.chat_hint").withStyle(ChatFormatting.GRAY));
                this.toolTip.add(Component.translatable("create_blueprinted.gui.schematic_table.render_button.chat_hint_visibility").withStyle(ChatFormatting.DARK_GRAY));

                super.doRender(graphics, mouseX, mouseY, partialTicks);
            }
        };

        renderButton.withCallback(() -> {
            if (schematicsArea == null) return;

            int index = schematicsArea.getState();
            List<Component> availableSchematics = CreateClient.SCHEMATIC_SENDER.getAvailableSchematics();
            if (index < 0 || index >= availableSchematics.size()) return;

            String filename = availableSchematics.get(index).getString();
            if (filename.endsWith(".nbt")) {
                filename = filename.substring(0, filename.length() - 4);
            }

            boolean shiftActive = Screen.hasShiftDown() && !brassworks$shiftWasDownOnInit;
            boolean ctrlActive = Screen.hasControlDown() && !brassworks$ctrlWasDownOnInit;

            int resolution = shiftActive ? 1024 : 256;
            String orientation = ctrlActive ? "left" : "right";

            if (Minecraft.getInstance().player != null) {
                CommandSourceStack source = Minecraft.getInstance().player.createCommandSourceStack();
                CreateSchematicExporter.export(source, filename, orientation, resolution);
                Minecraft.getInstance().setScreen(null);
            }
        });

        this.addRenderableWidget(renderButton);
        List<Rect2i> newExtraAreas = new ArrayList<>(this.extraAreas);
        newExtraAreas.add(new Rect2i(renderButtonX, renderButtonY, renderButton.getWidth(), renderButton.getHeight()));
        this.extraAreas = ImmutableList.copyOf(newExtraAreas);
    }

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void onContainerTickTail(CallbackInfo ci) {
        if (schematicsArea != null && schematicsLabel != null && schematicsLabel.text != null) {
            String originalText = schematicsLabel.text.getString();
            if (!originalText.isEmpty()) {
                int maxWidth = schematicsArea.getWidth() - 5;
                String truncatedText = UiHelpers.truncateString(Minecraft.getInstance().font, originalText, maxWidth);
                schematicsLabel.text = Component.literal(truncatedText);
            }
        }
    }
}