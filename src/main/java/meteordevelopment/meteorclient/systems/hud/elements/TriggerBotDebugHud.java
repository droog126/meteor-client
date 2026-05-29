package meteordevelopment.meteorclient.systems.hud.elements;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.combat.TriggerBotV2;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class TriggerBotDebugHud extends HudElement {
    public static final HudElementInfo<TriggerBotDebugHud> INFO = new HudElementInfo<>(
            Hud.GROUP,
            "triggerbot-debug",
            "显示 TriggerBot 的调试信息。",
            TriggerBotDebugHud::new
    );

    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<SettingColor> textColor = sgColors.add(new ColorSetting.Builder()
            .name("text-color").description("文本颜色。").defaultValue(new SettingColor(255, 255, 255)).build());

    private final Setting<SettingColor> critColor = sgColors.add(new ColorSetting.Builder()
            .name("crit-color").description("暴击时的颜色。").defaultValue(new SettingColor(255, 215, 0)).build());

    private final Setting<SettingColor> backgroundColor = sgColors.add(new ColorSetting.Builder()
            .name("background-color").description("背景颜色。").defaultValue(new SettingColor(25, 25, 25, 170)).build());

    private TriggerBotDebugHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        calculateSize(renderer);
    }

    private void calculateSize(HudRenderer renderer) {
        double maxWidth = 0;
        double totalHeight = 0;

        // 标题
        String title = "=== TriggerBot Debug ===";
        maxWidth = Math.max(maxWidth, renderer.textWidth(title, true));
        totalHeight += renderer.textHeight(true);

        // 当前冷却等状态信息
        String cooldownText = "";
        String firstHitText = "";
        String skipSprintText = "";
        if (mc.player != null) {
            cooldownText = String.format("当前冷却: %.3f", mc.player.getAttackCooldownProgress(0.5f));
            firstHitText = "首刀: ?";
            skipSprintText = "SkipSprint: ?";
        }
        maxWidth = Math.max(maxWidth, renderer.textWidth(cooldownText, true));
        maxWidth = Math.max(maxWidth, renderer.textWidth(firstHitText, true));
        maxWidth = Math.max(maxWidth, renderer.textWidth(skipSprintText, true));
        totalHeight += renderer.textHeight(true) * 3;

        // 攻击记录
        totalHeight += 5;
        for (TriggerBotV2.AttackRecord record : TriggerBotV2.attackRecords) {
            String line1 = String.format("[%d] %s", TriggerBotV2.attackRecords.indexOf(record) + 1, record.targetName);
            String acquisitionText = record.acquisition != null ? record.acquisition.displayName : "?";
            String line2 = String.format("  CD: %.3f | Dist: %.2f | %s | %s | %s",
                record.cooldownUsed, record.distance,
                record.isCrit ? "CRIT" : "Normal",
                record.skipSprint ? "Skip" : "Normal",
                acquisitionText);
            maxWidth = Math.max(maxWidth, renderer.textWidth(line1, true));
            maxWidth = Math.max(maxWidth, renderer.textWidth(line2, true));
            totalHeight += renderer.textHeight(true) * 2 + 3;
        }

        setSize(maxWidth + 12, totalHeight + 10);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (renderer == null) return;

        double x = this.x;
        double y = this.y;
        double width = getWidth();
        double height = getHeight();

        if (backgroundColor == null || textColor == null || critColor == null) return;

        try {
            Color bgColor = new Color(backgroundColor.get());
            renderer.quad(x, y, width, height, bgColor);

            double currentY = y + 5;

            // 标题
            String title = "=== TriggerBot Debug ===";
            renderer.text(title, x + 6, currentY, new Color(textColor.get()), true);
            currentY += renderer.textHeight(true);

            // 状态信息
            if (mc.player != null) {
                String cooldownText = String.format("当前冷却: %.3f", mc.player.getAttackCooldownProgress(0.5f));
                renderer.text(cooldownText, x + 6, currentY, new Color(textColor.get()), true);
                currentY += renderer.textHeight(true);

                String firstHitText = "首刀: ?";
                renderer.text(firstHitText, x + 6, currentY, new Color(textColor.get()), true);
                currentY += renderer.textHeight(true);

                String skipSprintText = "SkipSprint: " + (TriggerBotV2.attackRecords.isEmpty() ? "?" : (TriggerBotV2.attackRecords.get(0).skipSprint ? "开启" : "关闭"));
                renderer.text(skipSprintText, x + 6, currentY, new Color(textColor.get()), true);
                currentY += renderer.textHeight(true) + 5;

                // 攻击记录
                if (TriggerBotV2.attackRecords.isEmpty()) {
                    String noRecordText = "无攻击记录";
                    renderer.text(noRecordText, x + 6, currentY, new Color(200, 200, 200), true);
                } else {
                    for (int i = 0; i < TriggerBotV2.attackRecords.size(); i++) {
                        TriggerBotV2.AttackRecord record = TriggerBotV2.attackRecords.get(i);

                        String line1 = String.format("[%d] %s", i + 1, record.targetName);
                        renderer.text(line1, x + 6, currentY, new Color(textColor.get()), true);
                        currentY += renderer.textHeight(true);

                        String acquisitionText = record.acquisition != null ? record.acquisition.displayName : "?";
                        String line2 = String.format("  CD: %.3f | Dist: %.2f | %s | %s | %s",
                            record.cooldownUsed, record.distance,
                            record.isCrit ? "CRIT" : "Normal",
                            record.skipSprint ? "Skip" : "Normal",
                            acquisitionText);
                        Color color = record.isCrit ? new Color(critColor.get()) : new Color(textColor.get());
                        renderer.text(line2, x + 6, currentY, color, true);
                        currentY += renderer.textHeight(true) + 3;
                    }
                }
            }
        } catch (Exception e) {
            // 捕获异常，防止崩溃
        }
    }
}
