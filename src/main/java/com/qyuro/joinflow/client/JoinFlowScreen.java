package com.qyuro.joinflow.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small non-pausing fallback screen. It is opt-in because the HUD overlay is less intrusive. */
public final class JoinFlowScreen extends Screen {
    public JoinFlowScreen() {
        super(Component.literal("Qyuro JoinFlow"));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        drawPanel(graphics, width, height, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        ClientJoinFlow.dismissScreen();
        super.onClose();
    }

    public static void drawPanel(GuiGraphics graphics, int screenWidth, int screenHeight, boolean compact) {
        if (!ClientJoinFlow.isActive()) return;
        int panelWidth = compact ? 230 : 300;
        int panelHeight = compact ? 62 : 104;
        int x = (screenWidth - panelWidth) / 2;
        int y = compact ? Math.max(8, screenHeight - panelHeight - 12) : (screenHeight - panelHeight) / 2;
        int background = 0xD0101520;
        int border = 0xFF36B7A2;
        int accent = 0xFF47D7C4;
        int textColor = 0xFFF0F4F7;
        Minecraft minecraft = Minecraft.getInstance();

        graphics.fill(x, y, x + panelWidth, y + panelHeight, background);
        graphics.fill(x, y, x + panelWidth, y + 2, border);
        graphics.drawString(minecraft.font, "QYURO JOINFLOW", x + 12, y + 9, accent, false);
        graphics.drawString(minecraft.font, compact ? "Preparing connection" : ClientJoinFlow.message(),
                x + 12, y + 23, textColor, false);

        String stateText = "State: " + ClientJoinFlow.loadState().name();
        String progressText = ClientJoinFlow.progressPercent() + "%";
        if (ClientConfig.showProgress()) {
            int barY = y + (compact ? 39 : 49);
            int barWidth = panelWidth - 24;
            graphics.fill(x + 12, barY, x + 12 + barWidth, barY + 7, 0xFF29323D);
            int filled = Math.max(0, Math.min(barWidth, Math.round(barWidth * ClientJoinFlow.progressPercent() / 100.0f)));
            if (filled > 0) graphics.fill(x + 12, barY, x + 12 + filled, barY + 7, accent);
        }
        if (compact) {
            if (ClientConfig.showProgress()) graphics.drawString(minecraft.font, progressText, x + panelWidth - 36, y + 9, textColor, false);
            if (ClientConfig.showLoadState()) graphics.drawString(minecraft.font, ClientJoinFlow.loadState().name(), x + 12, y + 48, 0xFF9BA8B6, false);
        } else {
            if (ClientConfig.showLoadState()) graphics.drawString(minecraft.font, stateText, x + 12, y + 65, textColor, false);
            if (ClientConfig.showProgress()) graphics.drawString(minecraft.font, progressText, x + panelWidth - 42, y + 65, textColor, false);
            graphics.drawString(minecraft.font, "JoinFlow-owned work only", x + 12, y + 82, 0xFF9BA8B6, false);
        }
    }
}
