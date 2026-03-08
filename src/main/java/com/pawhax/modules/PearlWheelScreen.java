package com.pawhax.modules;

import com.pawhax.PawHax;
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

    Module pearlGUI = Modules.get().get(PearlGUI.class);

    public PearlWheelScreen(List<String> labels, List<String> commands) {
        super(Text.literal("Pearl Wheel"));
        this.labels = labels;
        this.commands = commands;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int centerX = width / 2;
        int centerY = height / 2;

        // Calculate which slot is hovered
        hoveredSlot = getHoveredSlot(mouseX, mouseY, centerX, centerY);

        // Draw the wheel
        for (int i = 0; i < SLOTS; i++) {
            double startAngle = (i * 2 * Math.PI / SLOTS) - Math.PI / 2 - Math.PI / SLOTS;
            double endAngle = ((i + 1) * 2 * Math.PI / SLOTS) - Math.PI / 2 - Math.PI / SLOTS;

            int color = (i == hoveredSlot) ? 0xAA4488FF : 0xAA222222;

            // Draw slice as filled triangular segments
            drawSlice(context, centerX, centerY, startAngle, endAngle, color);

            // Draw label
            if (i < labels.size()) {
                double midAngle = (startAngle + endAngle) / 2;
                int labelRadius = (OUTER_RADIUS + INNER_RADIUS) / 2;
                int labelX = centerX + (int) (Math.cos(midAngle) * labelRadius);
                int labelY = centerY + (int) (Math.sin(midAngle) * labelRadius);

                String label = labels.get(i);
                int textWidth = textRenderer.getWidth(label);
                context.drawText(textRenderer, label, labelX - textWidth / 2, labelY - 4, 0xFFFFFFFF, true);
            }
        }

        // Draw center circle
        drawCircle(context, centerX, centerY, INNER_RADIUS, 0xAA000000);
    }

    private void drawSlice(DrawContext context, int cx, int cy, double startAngle, double endAngle, int color) {
        int segments = 20;
        for (int j = 0; j < segments; j++) {
            double a1 = startAngle + (endAngle - startAngle) * j / segments;
            double a2 = startAngle + (endAngle - startAngle) * (j + 1) / segments;

            int x1 = cx + (int) (Math.cos(a1) * INNER_RADIUS);
            int y1 = cy + (int) (Math.sin(a1) * INNER_RADIUS);
            int x2 = cx + (int) (Math.cos(a1) * OUTER_RADIUS);
            int y2 = cy + (int) (Math.sin(a1) * OUTER_RADIUS);
            int x3 = cx + (int) (Math.cos(a2) * OUTER_RADIUS);
            int y3 = cy + (int) (Math.sin(a2) * OUTER_RADIUS);
            int x4 = cx + (int) (Math.cos(a2) * INNER_RADIUS);
            int y4 = cy + (int) (Math.sin(a2) * INNER_RADIUS);

            // Draw as two triangles forming a quad
            fillTriangle(context, x1, y1, x2, y2, x3, y3, color);
            fillTriangle(context, x1, y1, x3, y3, x4, y4, color);
        }
    }

    private void fillTriangle(DrawContext context, int x1, int y1, int x2, int y2, int x3, int y3, int color) {
        // Simple bounding box fill approach for triangles
        int minX = Math.min(x1, Math.min(x2, x3));
        int maxX = Math.max(x1, Math.max(x2, x3));
        int minY = Math.min(y1, Math.min(y2, y3));
        int maxY = Math.max(y1, Math.max(y2, y3));

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (pointInTriangle(x, y, x1, y1, x2, y2, x3, y3)) {
                    context.fill(x, y, x + 1, y + 1, color);
                }
            }
        }
    }

    private boolean pointInTriangle(int px, int py, int x1, int y1, int x2, int y2, int x3, int y3) {
        int d1 = sign(px, py, x1, y1, x2, y2);
        int d2 = sign(px, py, x2, y2, x3, y3);
        int d3 = sign(px, py, x3, y3, x1, y1);

        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);

        return !(hasNeg && hasPos);
    }

    private int sign(int px, int py, int x1, int y1, int x2, int y2) {
        return (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2);
    }

    private void drawCircle(DrawContext context, int cx, int cy, int radius, int color) {
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + y * y <= radius * radius) {
                    context.fill(cx + x, cy + y, cx + x + 1, cy + y + 1, color);
                }
            }
        }
    }

    private int getHoveredSlot(int mouseX, int mouseY, int centerX, int centerY) {
        int dx = mouseX - centerX;
        int dy = mouseY - centerY;
        double distance = Math.sqrt(dx * dx + dy * dy);

        if (distance < INNER_RADIUS || distance > OUTER_RADIUS) {
            return -1;
        }

        double angle = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / SLOTS;
        if (angle < 0) angle += 2 * Math.PI;

        int slot = (int) (angle / (2 * Math.PI / SLOTS)) % SLOTS;
        return slot;
    }

    public String generateAntispam(int length) {
        // create random bytes
        byte[] bytes = new byte[length];
        new java.util.Random().nextBytes(bytes);

        // add them to a string
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }

        return "[" + sb + "]";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hoveredSlot >= 0 && hoveredSlot < commands.size()) {
            String command = commands.get(hoveredSlot);
            if (command != null && !command.isEmpty() && mc.player != null) {
                if (command.startsWith("/")) {
                    mc.player.networkHandler.sendChatCommand(command.substring(1) + " " + generateAntispam((int)pearlGUI.settings.get("anti-spam-bytes-message").get()));
                } else {
                    mc.player.networkHandler.sendChatMessage(command + " " + generateAntispam((int)pearlGUI.settings.get("anti-spam-bytes-chat").get()));
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
