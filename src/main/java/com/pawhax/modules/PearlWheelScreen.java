package com.pawhax.modules;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Random;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class PearlWheelScreen extends Screen {
    private static final int SLOTS = 8;
    private static final int OUTER_RADIUS = 100;
    private static final int INNER_RADIUS = 30;
    private static final Random RANDOM = new Random();

    private final List<String> labels;
    private final List<String> commands;
    private final int antispamBytesMessage;
    private final int antispamBytesChat;
    private final boolean scrollWheelPaging;
    private final int itemCount;
    private int hoveredSlot = -1;
    private int hoveredArrow = -1; // -1=none, 0=left, 1=right
    private int currentPage = 0;

    public PearlWheelScreen(List<String> labels, List<String> commands, int antispamBytesMessage, int antispamBytesChat, boolean scrollWheelPaging) {
        super(Text.literal("Pearl Wheel"));
        this.labels = labels;
        this.commands = commands;
        this.antispamBytesMessage = antispamBytesMessage;
        this.antispamBytesChat = antispamBytesChat;
        this.scrollWheelPaging = scrollWheelPaging;
        this.itemCount = Math.min(labels.size(), commands.size());
    }

    private int totalPages() {
        return Math.max(1, (int) Math.ceil(itemCount / (double) SLOTS));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int centerX = width / 2;
        int centerY = height / 2;
        int total = totalPages();

        hoveredSlot = getHoveredSlot(mouseX, mouseY, centerX, centerY);
        hoveredArrow = getHoveredArrow(mouseX, mouseY, centerX, centerY, total);

        drawWheel(context, centerX, centerY);

        int pageStart = currentPage * SLOTS;
        for (int i = 0; i < SLOTS; i++) {
            int idx = pageStart + i;
            if (idx >= itemCount) break;
            double startAngle = (i * 2 * Math.PI / SLOTS) - Math.PI / 2 - Math.PI / SLOTS;
            double endAngle = ((i + 1) * 2 * Math.PI / SLOTS) - Math.PI / 2 - Math.PI / SLOTS;
            double midAngle = (startAngle + endAngle) / 2;
            int labelRadius = (OUTER_RADIUS + INNER_RADIUS) / 2;
            int labelX = centerX + (int)(Math.cos(midAngle) * labelRadius);
            int labelY = centerY + (int)(Math.sin(midAngle) * labelRadius);
            String label = labels.get(idx);
            int textWidth = textRenderer.getWidth(label);
            context.drawText(textRenderer, label, labelX - textWidth / 2, labelY - 4, 0xFFFFFFFF, true);
        }

        drawCircle(context, centerX, centerY, INNER_RADIUS, 0xAA000000);

        if (total > 1) {
            String leftArrow = "<";
            String rightArrow = ">";
            String pageText = (currentPage + 1) + "/" + total;

            int lw = textRenderer.getWidth(leftArrow);
            int pw = textRenderer.getWidth(pageText);
            int rw = textRenderer.getWidth(rightArrow);
            int gap = 4;
            int totalW = lw + gap + pw + gap + rw;
            int startX = centerX - totalW / 2;
            int textY = centerY - 4;

            int leftColor  = (hoveredArrow == 0) ? 0xFF88BBFF : 0xFFAAAAAA;
            int rightColor = (hoveredArrow == 1) ? 0xFF88BBFF : 0xFFAAAAAA;

            context.drawText(textRenderer, leftArrow,  startX,                        textY, leftColor,  true);
            context.drawText(textRenderer, pageText,   startX + lw + gap,             textY, 0xFFFFFFFF, true);
            context.drawText(textRenderer, rightArrow, startX + lw + gap + pw + gap,  textY, rightColor, true);
        }
    }

    private int getHoveredArrow(int mouseX, int mouseY, int centerX, int centerY, int totalPages) {
        if (totalPages <= 1) return -1;
        int dx = mouseX - centerX;
        int dy = mouseY - centerY;
        if (dx * dx + dy * dy > INNER_RADIUS * INNER_RADIUS) return -1;
        if (dx < 0) return 0;
        if (dx > 0) return 1;
        return -1;
    }

    private void drawWheel(DrawContext context, int cx, int cy) {
        int pageStart = currentPage * SLOTS;
        int orSq = OUTER_RADIUS * OUTER_RADIUS;
        int irSq = INNER_RADIUS * INNER_RADIUS;

        for (int py = cy - OUTER_RADIUS; py <= cy + OUTER_RADIUS; py++) {
            int dy = py - cy;
            int spanColor = 0;
            int spanX = -1;

            for (int px = cx - OUTER_RADIUS; px <= cx + OUTER_RADIUS; px++) {
                int dx = px - cx;
                int dSq = dx * dx + dy * dy;
                int color = 0;
                if (dSq >= irSq && dSq <= orSq) {
                    double angle = Math.atan2(dy, dx) + Math.PI / 2 + Math.PI / SLOTS;
                    if (angle < 0) angle += 2 * Math.PI;
                    int slot = (int)(angle / (2 * Math.PI / SLOTS)) % SLOTS;
                    int idx = pageStart + slot;
                    boolean hasItem = idx < itemCount;
                    color = (slot == hoveredSlot && hasItem) ? 0xAA4488FF
                          : (hasItem ? 0xAA222222 : 0xAA111111);
                }
                if (color != spanColor) {
                    if (spanColor != 0) context.fill(spanX, py, px, py + 1, spanColor);
                    spanColor = color;
                    spanX = px;
                }
            }
            if (spanColor != 0) context.fill(spanX, py, cx + OUTER_RADIUS + 1, py + 1, spanColor);
        }
    }

    private void drawCircle(DrawContext context, int cx, int cy, int radius, int color) {
        int rSq = radius * radius;
        for (int py = cy - radius; py <= cy + radius; py++) {
            int dy = py - cy;
            int hw = (int) Math.sqrt(rSq - dy * dy);
            context.fill(cx - hw, py, cx + hw + 1, py + 1, color);
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

    private String generateAntispam(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return "[" + sb + "]";
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int centerX = width / 2;
            int centerY = height / 2;
            int total = totalPages();

            // Arrow clicks inside the center circle
            if (total > 1) {
                int dx = (int) mouseX - centerX;
                int dy = (int) mouseY - centerY;
                if (dx * dx + dy * dy <= INNER_RADIUS * INNER_RADIUS) {
                    if (dx < 0) currentPage = (currentPage - 1 + total) % total;
                    else if (dx > 0) currentPage = (currentPage + 1) % total;
                    return true;
                }
            }

            // Slot clicks on the wheel
            if (hoveredSlot >= 0) {
                int idx = currentPage * SLOTS + hoveredSlot;
                if (idx < itemCount) {
                    String command = commands.get(idx);
                    if (command != null && !command.isEmpty() && mc.player != null) {
                        if (command.startsWith("/")) {
                            mc.player.networkHandler.sendChatCommand(command.substring(1) + " " + generateAntispam(antispamBytesMessage));
                        } else {
                            mc.player.networkHandler.sendChatMessage(command + " " + generateAntispam(antispamBytesChat));
                        }
                    }
                    close();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int total = totalPages();
        if (scrollWheelPaging && total > 1) {
            if (verticalAmount > 0) currentPage = (currentPage - 1 + total) % total;
            else if (verticalAmount < 0) currentPage = (currentPage + 1) % total;
        }
        return true;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
