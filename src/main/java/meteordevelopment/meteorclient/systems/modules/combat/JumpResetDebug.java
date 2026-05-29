package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

public class JumpResetDebug extends Module {
    public JumpResetDebug() {
        super(Categories.Combat, "debug面板", "显示调试信息。");
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null) return;

        DrawContext drawContext = event.drawContext;
        int x = 10;
        int y = 10;

        drawContext.drawText(mc.textRenderer, Text.literal("=== Debug 面板 ==="), x, y, new Color(255,255,255).getPacked(), true);
        y += 15;

        if (TriggerBotV2.attackRecords.isEmpty()) {
            drawContext.drawText(mc.textRenderer, Text.literal("无攻击记录"), x, y, new Color(200,200,200).getPacked(), true);
            return;
        }

        for (int i = 0; i < TriggerBotV2.attackRecords.size(); i++) {
            TriggerBotV2.AttackRecord record = TriggerBotV2.attackRecords.get(i);

            String line1 = String.format("[%d] %s", i + 1, record.targetName);
            drawContext.drawText(mc.textRenderer, Text.literal(line1), x, y, new Color(255,255,255).getPacked(), true);
            y += 12;

            String line2 = String.format("  CD: %.3f | Dist: %.2f | %s | %s",
                record.cooldownUsed,
                record.distance,
                record.isCrit ? "CRIT" : "Normal",
                record.skipSprint ? "Skip" : "Normal");
            int color = record.isCrit ? new Color(255,215,0).getPacked() : new Color(255,255,255).getPacked();
            drawContext.drawText(mc.textRenderer, Text.literal(line2), x, y, color, true);
            y += 12 + 3;
        }
    }
}