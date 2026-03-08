package com.pawhax.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PearlWheelScreen extends Screen {
    private static final int SLOTS = 8;
    private static final int OUTER_RADIUS = 100;
    private static final int INNER_RADIUS = 30;

    private final List<String> labels;
    private final List<String> commands;
    private int hoveredSlot = -1;

    private static final java.util.Random RANDOM = new java.util.Random();

    Module pearlGUI = Modules.get().get(PearlGUI.class);

    public PearlWheelScreen(List<String> labels, List<String> commands) {
        super(Text.literal("Pearl Wheel"));
        this.labels = labels;
        this.commands = commands;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cx = width / 2;
        int cy = height / 2;

        hoveredSlot = getHoveredSlot(mouseX, mouseY, cx, cy);

        // Draw each slice by iterating over the bounding box of the wheel and
        // checking which slice each pixel belongs to. One fill() call per scanline
        // segment rather than per pixel.
        int outerR2 = OUTER_RADIUS * OUTER_RADIUS;
        int innerR2 = INNER_RADIUS * INNER_RADIUS;

        for (int dy = -OUTER_RADIUS; dy <= OUTER_RADIUS; dy++) {
            int runStart = Integer.MIN_VALUE;
            int runSlot = -2; // -2 = no run in progress
            int runColor = 0;

            for (int dx = -OUTER_RADIUS; dx <= OUTER_RADIUS; dx++) {
                int dist2 = dx * dx + dy * dy;
                boolean inRing = dist2 <= outerR2 && dist2 > innerR2;

                int slot = -1;
                int color = 0;
                if (inRing) {
                    double angle = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / SLOTS;
                    if (angle < 0) angle += 2 * Math.PI;
                    slot = (int)(angle / (2 * Math.PI / SLOTS)) % SLOTS;
                    color = (slot == hoveredSlot) ? 0xAA4488FF : 0xAA222222;
                }

                if (slot == runSlot && color == runColor) {
                    // extend current run
                    continue;
                }

                // flush previous run
                if (runSlot != -2 && runSlot != -1) {
                    context.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, runColor);
                }

                runStart = dx;
                runSlot = slot;
                runColor = color;
            }

            // flush last run in row
            if (runSlot != -2 && runSlot != -1) {
                context.fill(cx + runStart, cy + dy, cx + OUTER_RADIUS + 1, cy + dy + 1, runColor);
            }
        }

        // Draw center circle using scanline runs
        for (int dy = -INNER_RADIUS; dy <= INNER_RADIUS; dy++) {
            int runStart = Integer.MIN_VALUE;
            boolean inRun = false;
            for (int dx = -INNER_RADIUS; dx <= INNER_RADIUS; dx++) {
                boolean inside = dx * dx + dy * dy <= innerR2;
                if (inside && !inRun) {
                    runStart = dx;
                    inRun = true;
                } else if (!inside && inRun) {
                    context.fill(cx + runStart, cy + dy, cx + dx, cy + dy + 1, 0xAA000000);
                    inRun = false;
                }
            }
            if (inRun) {
                context.fill(cx + runStart, cy + dy, cx + INNER_RADIUS + 1, cy + dy + 1, 0xAA000000);
            }
        }

        // Draw labels on top
        for (int i = 0; i < SLOTS; i++) {
            if (i >= labels.size()) continue;
            double startAngle = (i * 2 * Math.PI / SLOTS) - Math.PI / 2 - Math.PI / SLOTS;
            double endAngle   = ((i + 1) * 2 * Math.PI / SLOTS) - Math.PI / 2 - Math.PI / SLOTS;
            double midAngle   = (startAngle + endAngle) / 2;
            int labelRadius   = (OUTER_RADIUS + INNER_RADIUS) / 2;
            int labelX = cx + (int)(Math.cos(midAngle) * labelRadius);
            int labelY = cy + (int)(Math.sin(midAngle) * labelRadius);
            String label = labels.get(i);
            int textWidth = textRenderer.getWidth(label);
            context.drawText(textRenderer, label, labelX - textWidth / 2, labelY - 4, 0xFFFFFFFF, true);
        }
    }

    private int getHoveredSlot(int mouseX, int mouseY, int centerX, int centerY) {
        int dx = mouseX - centerX;
        int dy = mouseY - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);
        if (distance < INNER_RADIUS || distance > OUTER_RADIUS) return -1;
        double angle = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / SLOTS;
        if (angle < 0) angle += 2 * Math.PI;
        return (int)(angle / (2 * Math.PI / SLOTS)) % SLOTS;
    }

    public String generateAntispam(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return "[" + sb + "]";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hoveredSlot >= 0 && hoveredSlot < commands.size()) {
            String command = commands.get(hoveredSlot);
            if (command != null && !command.isEmpty() && mc.player != null) {
                if (command.startsWith("/")) {
                    mc.player.networkHandler.sendChatCommand(command.substring(1) + " " + generateAntispam((int) pearlGUI.settings.get("anti-spam-bytes-message").get()));
                } else {
                    mc.player.networkHandler.sendChatMessage(command + " " + generateAntispam((int) pearlGUI.settings.get("anti-spam-bytes-chat").get()));
                }
            }
            close();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
