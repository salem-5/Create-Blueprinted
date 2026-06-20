package net.swzo.create_blueprinted.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.swzo.create_blueprinted.util.UiHelpers;

import java.util.concurrent.atomic.AtomicReference;

public final class RenderProgress {

    public enum Stage {
        PARSING(0.05f, "parsing"),
        BUILDING(0.35f, "building"),
        RENDERING(0.75f, "rendering"),
        SAVING(0.95f, "saving"),
        DONE(1.00f, "done");

        public final float fraction;
        public final String key;

        Stage(float fraction, String key) {
            this.fraction = fraction;
            this.key = key;
        }
    }

    private static final int BAR_SEGMENTS = 12;
    private static final long LINGER_MILLIS = 2500L;

    private record State(String name, float fraction, Component message, long clearAt) {}

    private static final AtomicReference<State> STATE = new AtomicReference<>(null);

    private RenderProgress() {}

    public static void start(String name) {
        STATE.set(new State(name, 0f, buildMessage(name, 0f, false, false), 0L));
    }

    public static void stage(Stage stage, String name) {
        STATE.set(new State(name, stage.fraction, buildMessage(name, stage.fraction, false, false), 0L));
    }

    public static void success(String name) {
        STATE.set(new State(name, 1f, buildMessage(name, 1f, true, false), now() + LINGER_MILLIS));
    }

    public static void fail(String name) {
        STATE.set(new State(name, 0f, buildMessage(name, 0f, false, true), now() + LINGER_MILLIS));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        State state = STATE.get();
        if (state == null) return;

        if (state.clearAt() > 0L && now() >= state.clearAt()) {
            STATE.compareAndSet(state, null);
            return;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(state.message(), true);
        }
    }

    private static Component buildMessage(String name, float fraction, boolean done, boolean failed) {
        float clamped = Math.max(0f, Math.min(1f, fraction));
        int filled = Math.round(clamped * BAR_SEGMENTS);

        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < BAR_SEGMENTS; i++) {
            bar.append(i < filled ? '█' : '▒');
        }

        int barColor = failed ? UiHelpers.ERROR_PRIMARY
                : done ? 0x6FCF6F
                : UiHelpers.LIGHT_BLUE_TEXT_COLOR;

        String labelKey = failed ? "create_blueprinted.render.progress.failed"
                : done ? "create_blueprinted.render.progress.done"
                : "create_blueprinted.render.progress.rendering";

        MutableComponent message = Component.translatable(labelKey, name)
                .withStyle(s -> s.withColor(UiHelpers.DARK_BLUE_TEXT_COLOR));
        message.append(Component.literal(" " + bar).withStyle(Style.EMPTY.withColor(barColor)));
        if (!failed) {
            message.append(Component.literal(" " + Math.round(clamped * 100f) + "%")
                    .withStyle(s -> s.withColor(UiHelpers.LIGHT_BLUE_TEXT_COLOR)));
        }
        return message;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
