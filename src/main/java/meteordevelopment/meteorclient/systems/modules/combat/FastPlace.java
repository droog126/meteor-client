package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.KeyBindingAccessor;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

public class FastPlace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyBlocks = sgGeneral.add(new BoolSetting.Builder()
            .name("only-blocks").description("Only works when holding placeable blocks").defaultValue(true).build());

    private final Setting<Integer> cps = sgGeneral.add(new IntSetting.Builder()
            .name("cps").description("Clicks per second").defaultValue(5).min(1).sliderMax(20).build());

    private int placeTimer = 0;

    public FastPlace() {
        super(Categories.Combat, "fast-place", "Places blocks faster with simulated key clicks.");
    }

    @Override
    public void onActivate() {
        placeTimer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // 检查是否只在持有方块时工作
        if (onlyBlocks.get()) {
            ItemStack mainHand = mc.player.getMainHandStack();
            ItemStack offHand = mc.player.getOffHandStack();
            if (!(mainHand.getItem() instanceof BlockItem) && !(offHand.getItem() instanceof BlockItem)) {
                return;
            }
        }

        // 计算放置间隔（ticks）：5 CPS = 每 4t 一次
        int placeInterval = Math.max(1, 20 / cps.get());
        placeTimer++;

        if (placeTimer >= placeInterval) {
            placeTimer = 0;

            // 使用 KeyBindingAccessor 模拟按键点击
            KeyBindingAccessor accessor = (KeyBindingAccessor) mc.options.useKey;
            accessor.meteor$setTimesPressed(accessor.meteor$getTimesPressed() + 1);
        }
    }
}
