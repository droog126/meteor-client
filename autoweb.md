# AutoWeb 模块逻辑文档

修改流体渲染：只在 triggered（按住绑定键）时才渲染
场景优先级（高→低）
aimed 在蜘蛛网上空桶收水 > 岩浆 > 放网
aimed 不在蜘蛛网上空桶收水 > 手持物 > 按预测面决定（岩浆面用岩浆，网面用网）
通用手持啥优先用啥，但蜘蛛网上烧岩浆优先级 > 手持，空桶收水 > 一切
如果没有空桶，目标是水，则会对母体方块放置水重合，然后把水再收回来

## 1. 模块概述

AutoWeb 是一个 Meteor Client 的 Combat 模块，用于在 PvP 中自动放置蜘蛛网（Cobweb）或岩浆（Lava）来限制敌人移动，同时支持用空桶收集水或岩浆。

核心设计目标：
- **防检测**：使用物理切槽（真实动画）+ 延迟切回，模拟人类操作
- **独立逻辑互斥**：每 tick 只执行一种动作，避免逻辑冲突
- **即时响应**：松开激活键立即切回原物品

---

## 2. 设置项 (Settings)

| 设置名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| trigger-mode | Enum | HOLD | 触发模式：HOLD=按住激活键才工作，ALWAYS=一直自动运行 |
| activate | Keybind | 鼠标侧键(3) | 仅在 HOLD 模式下生效的激活键 |
| range | Double | 7.0 | 最大作用距离 (1-12) |
| delay | Int | 3 | 放置/收集后的冷却 tick 数 (0-20) |
| predict-ticks | Int | 2 | 对目标移动进行预测的 tick 数 (0-10) |
| debug | Bool | true | 在聊天框输出详细日志 |
| web-color | Color | (0,200,255,40) | 蜘蛛网渲染填充色 |
| web-line | Color | (0,200,255,255) | 蜘蛛网渲染线框色 |
| lava-color | Color | (255,80,0,40) | 岩浆渲染填充色 |
| lava-line | Color | (255,80,0,255) | 岩浆渲染线框色 |
| aim-color | Color | (0,255,0,60) | 瞄准命中渲染填充色（绿色） |
| aim-line | Color | (0,255,0,255) | 瞄准命中渲染线框色（绿色） |

---

## 3. 状态变量 (State)

| 变量名 | 类型 | 说明 |
|--------|------|------|
| renderPos | List<BlockPos> | 当前可放置位置的渲染列表 |
| renderFace | Map<BlockPos, Direction> | 每个位置的最佳放置面 |
| aimed | BlockPos | 当前准星瞄准的可放置位置 |
| cd | int | 冷却计数器，>0 时不执行动作 |
| lockedTarget | LivingEntity | 当前锁定的目标实体 |
| prevSlot | int | 切槽前手持物品槽位，-1 表示未记录 |
| swapBackTimer | int | 延迟切回计数器，每 tick -1，到 0 时切回 |
| wasTriggered | boolean | 上一 tick 是否处于 triggered 状态 |
| lastAction | ActionType | 上一 tick 执行的动作类型 |

---

## 4. 核心逻辑流程

### 4.1 Tick 事件主循环 (onTick)

```
每一 tick 执行顺序：

1. 延迟切回处理
   if swapBackTimer > 0:
       swapBackTimer--
       if swapBackTimer == 0 && prevSlot != -1:
           setSelectedSlot(prevSlot)  // 物理切回
           prevSlot = -1

2. 冷却递减
   if cd > 0: cd--

3. 计算 triggered 状态
   holdMode = triggerMode == HOLD
   triggered = !holdMode || activateBind.isPressed()

4. 更新目标与渲染列表
   updateTarget()          // 从准星获取目标实体
   clearRender()           // 清空上一 tick 的渲染数据
   if 目标有效且距离内:
       buildPlaceable(target)  // 构建可放置位置（base, up, down）
       aimed = getLookedRenderPos()  // 获取准星瞄准的位置

5. 【独立逻辑 1】松开 activateBind 立即切回
   if wasTriggered && !triggered:      // 松开按键的那一刻
       if prevSlot != -1:
           setSelectedSlot(prevSlot)    // 立即切回
           prevSlot = -1
           swapBackTimer = 0            // 取消延迟切回
   wasTriggered = triggered              // 更新状态

6. 触发条件检查
   if !triggered: return       // 未触发不执行动作
   if cd > 0: return           // 冷却中不执行动作
   if aimed == null: return    // 没瞄准位置不执行动作

7. 获取目标方块状态
   aimState = world.getBlockState(aimed)
   fluidAtAim = aimState.getFluidState().getFluid()
   hasWaterAtAim = fluid == WATER || FLOWING_WATER
   hasLavaAtAim  = fluid == LAVA  || FLOWING_LAVA
   isCobweb = aimState.isOf(COBWEB)

8. 【独立逻辑 2】瞄准水/岩浆时切空桶收集（最高优先级）
   if hasWaterAtAim && hasInHotbar(BUCKET):
       executeAction(SCOOP_WATER, () -> scoopFluid(aimed, BUCKET, "收水"))
       return    // 执行后退出，不再执行放置逻辑
   if hasLavaAtAim && hasInHotbar(BUCKET):
       executeAction(SCOOP_LAVA, () -> scoopFluid(aimed, BUCKET, "收岩浆"))
       return    // 执行后退出，不再执行放置逻辑

9. 放置逻辑（只有上面没 return 才会执行到这里）
   canWeb  = hasInHotbar(COBWEB)     && isValidForWeb(aimed)
   canLava = hasInHotbar(LAVA_BUCKET) && isValidForLava(aimed)

   // 第一优先级：当前手持物品是啥就用啥
   mainHandItem = player.getMainHandStack().getItem()
   targetItem = null
   if mainHandItem == COBWEB     && canWeb  && !isCobweb:
       targetItem = COBWEB
   else if mainHandItem == LAVA_BUCKET && canLava:
       targetItem = LAVA_BUCKET

   // 第二优先级：如果该面是蜘蛛网，优先放岩浆覆盖
   // 第三优先级：网 > 岩浆
   if targetItem == null:
       if isCobweb && canLava:
           targetItem = LAVA_BUCKET
       else if canWeb:
           targetItem = COBWEB
       else if canLava:
           targetItem = LAVA_BUCKET

   // 执行放置
   if targetItem == COBWEB:
       executeAction(PLACE_WEB, () -> placeBlock(aimed, COBWEB, "蜘蛛网"))
   else if targetItem == LAVA_BUCKET:
       executeAction(PLACE_LAVA, () -> placeBlock(aimed, LAVA_BUCKET, "岩浆"))
```

---

## 5. 独立逻辑详解

### 5.1 独立逻辑 1：松开 activateBind 立即切回

**触发条件**：`wasTriggered == true` 且当前 `triggered == false`

**行为**：
- 立即调用 `setSelectedSlot(prevSlot)` 物理切回原物品
- 清空 `prevSlot = -1`
- 取消任何待执行的延迟切回 (`swapBackTimer = 0`)

**为什么需要**：
- 如果不做这个逻辑，模块会等待 `swapBackTimer` 倒计时结束才切回
- 如果用户在倒计时期间手动切槽，会被模块强制覆盖
- 松开键立即切回，给用户完全的手动控制权

**代码位置**：`onTick()` 中 triggered 判断之前

---

### 5.2 独立逻辑 2：瞄准水/岩浆时切空桶收集

**触发条件**：
- `triggered == true`（模块处于激活状态）
- `aimed != null`（准星瞄准了有效位置）
- 目标位置是 **水** 或 **岩浆**
- 背包热栏中有 **空桶**

**行为**：
- 物理切槽到空桶
- 对目标位置执行 `interactBlock` 尝试收集
- 设置 2 tick 的延迟切回

**优先级**：这是所有动作中**最高优先级**的。一旦触发，直接 `return`，不会执行任何放置逻辑。

**代码位置**：`onTick()` 中放置逻辑之前

---

## 6. 动作执行框架

### 6.1 ActionType 枚举

```java
private enum ActionType {
    NONE,       // 无动作
    PLACE_WEB,  // 放置蜘蛛网
    PLACE_LAVA, // 放置岩浆
    SCOOP_WATER,// 收集水
    SCOOP_LAVA  // 收集岩浆
}
```

### 6.2 executeAction 方法

```java
private void executeAction(ActionType type, Action action) {
    lastAction = type;    // 记录本次动作类型
    action.run();         // 执行具体动作
}
```

**设计目的**：
- 强制每 tick 只执行一种动作（通过 `return` 或代码流程控制）
- 记录 `lastAction` 便于调试和扩展（如后续加统计、日志、动画等）
- 使用函数式接口 `Action`，让调用方像传参数一样传代码块

---

## 7. 统一操作方法

### 7.1 placeBlock(BlockPos, Item, String)

**用途**：放置蜘蛛网或岩浆

**参数**：
- `targetPos`: 目标位置
- `item`: 要使用的物品（COBWEB 或 LAVA_BUCKET）
- `name`: 调试日志中显示的名称

**流程**：
1. `getSlot(item)` 查找物品槽位
2. 记录 `prevSlot`（如果还没记录）
3. `setSelectedSlot(slot)` 物理切槽
4. 从 `renderFace` 获取最佳放置面
5. 计算 `hitVec` 和 `BlockHitResult`
6. `interactBlock()` 尝试放置
7. 如果失败，尝试 `interactItem()`
8. 如果成功：`swingHand()` + `cd = delay.get()` + `swapBackTimer = 2`
9. 如果失败：`swapBackTimer = 1`（快速切回）

---

### 7.2 scoopFluid(BlockPos, Item, String)

**用途**：收集水或岩浆

**参数**：
- `targetPos`: 目标位置
- `item`: 要使用的物品（BUCKET）
- `name`: 调试日志中显示的名称

**流程**：与 `placeBlock` 完全一致，只是收集的是流体。

---

## 8. 环境合法性判断

### 8.1 isValidForWeb(BlockPos)

```java
return state.isReplaceable();
```

- 只要方块是可替换的（空气、草丛、水等），就可以放蜘蛛网
- **不检查实体碰撞**：即使有人站在上面也可以尝试放置

### 8.2 isValidForLava(BlockPos)

```java
if (!state.isReplaceable()) return false;
Fluid fluid = state.getFluidState().getFluid();
if (fluid == WATER || fluid == FLOWING_WATER) return false;
return true;
```

- 必须是可替换方块
- **不能是水**：岩浆遇到水会变成黑曜石/圆石，所以禁止在水上放岩浆

---

## 9. 渲染逻辑 (onRender)

**渲染时机**：每帧渲染，不依赖 triggered 状态

**每个可放置位置的渲染规则**：

```
if triggered && isAimed:
    渲染 aimColor（绿色）      // 激活状态 + 准星命中 = 绿色高亮
else:
    if canWeb:  渲染 webColor（青色）
    if canLava: 渲染 lavaColor（橙色），如果同时 canWeb 则偏移 0.008 避免重叠
    if !canWeb && !canLava: 渲染灰色
```

**设计意图**：
- Web/Lava 的渲染**一直显示**，让用户知道哪里可以放
- Aim 绿色**只在激活且瞄准时显示**，作为操作反馈

---

## 10. 物理切槽与延迟切回机制

### 10.1 为什么用物理切槽？

**问题**：`InvUtils.swap()` 是 Silent Swap（无声切槽），只发包不切客户端槽位，没有动画，容易被反作弊检测。

**解决**：直接修改 `selectedSlot`，客户端会播放切槽动画，服务器也能看到，完全模拟人类操作。

### 10.2 延迟切回时序

```
Tick N:   放置/收集成功 → prevSlot 记录原槽位 → setSelectedSlot(新槽位) → swapBackTimer = 2
Tick N+1: swapBackTimer = 1（保持新槽位）
Tick N+2: swapBackTimer = 0 → setSelectedSlot(prevSlot) → prevSlot = -1
```

**为什么延迟 2 tick**：
- 给服务器足够的时间处理放置/收集请求
- 模拟人类操作的自然间隔
- 避免同一 tick 内切来切去被检测

### 10.3 特殊情况：松开按键

如果在 `swapBackTimer` 倒计时期间松开了 `activateBind`：
- 独立逻辑 1 立即触发
- 无视 `swapBackTimer`，直接切回
- 这样用户随时可以拿回控制权

---

## 11. 目标锁定机制

### 11.1 updateTarget()

```java
if (crosshairTarget instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof LivingEntity living) {
    lockedTarget = living;
}
```

- 从准星直接获取目标（不依赖 TriggerBotV2）
- 只锁定生物实体（LivingEntity）

### 11.2 目标有效性检查

```java
lockedTarget != null && lockedTarget.isAlive() && lockedTarget.distanceTo(player) <= range
```

- 目标必须存活
- 目标必须在范围内

### 11.3 预测位置

```java
private BlockPos getPredictedPos(LivingEntity entity) {
    int ticks = predictTicks.get();
    if (ticks <= 0) return entity.getBlockPos();
    double dx = entity.getX() - entity.lastX;
    double dz = entity.getZ() - entity.lastZ;
    return BlockPos.ofFloored(entity.getEntityPos().add(dx * ticks, 0, dz * ticks));
}
```

- 根据目标的速度向量预测未来位置
- 只预测 XZ 平面，不预测 Y 轴（防止预判跳跃导致放头顶）

---

## 12. 放置位置构建 (buildPlaceable)

```java
private void buildPlaceable(LivingEntity target) {
    BlockPos base = getPredictedPos(target);
    tryAdd(base);      // 脚下
    tryAdd(base.up()); // 腰部
    tryAdd(base.down()); // 脚下下方（用于下落预判）
}
```

每个位置通过 `tryAdd` 检查：
1. `isValidForWeb || isValidForLava` — 环境合法
2. `bestFace(pos) != null` — 有有效的支撑面

`bestFace` 选择逻辑：
- 遍历 6 个方向
- 支撑方块不能是空气或可替换的
- 支撑方块必须在 range 内
- 选择距离玩家眼睛最近的支撑面

---

## 13. 准星瞄准检测 (getLookedRenderPos)

```java
Vec3d cameraPos = player.getCameraPosVec(1.0F);
Vec3d endPos = cameraPos.add(player.getRotationVec(1.0F).multiply(range));
BlockHitResult hit = world.raycast(new RaycastContext(...));

if (hit != null && hit.getType() == BLOCK) {
    BlockPos placePos = hit.getBlockPos().offset(hit.getSide());
    return renderPos.contains(placePos) ? placePos : null;
}
```

- 从眼睛发射射线
- 如果命中方块，计算放置位置（命中面外侧）
- 只有这个位置在 `renderPos` 列表中才算有效瞄准

---

## 14. 工具方法

| 方法名 | 功能 |
|--------|------|
| `getSlot(Item)` | 查找物品在热栏中的槽位，优先主手 |
| `hasInHotbar(Item)` | 判断热栏中是否有该物品 |
| `clearRender()` | 清空渲染列表和瞄准位置 |
| `placeAt(BlockPos, Direction)` | 底层放置方法（当前未直接使用） |
| `renderSupportFace(...)` | 渲染方块面的辅助方法 |

---

## 15. 扩展指南

### 15.1 添加新动作类型

1. 在 `ActionType` 枚举中添加新类型：
   ```java
   private enum ActionType { NONE, PLACE_WEB, PLACE_LAVA, SCOOP_WATER, SCOOP_LAVA, PLACE_TNT }
   ```

2. 在 `onTick` 的放置逻辑中添加新分支：
   ```java
   if (targetItem == Items.TNT) {
       executeAction(ActionType.PLACE_TNT, () -> placeBlock(aimed, Items.TNT, "TNT"));
   }
   ```

### 15.2 修改优先级

当前优先级（从高到低）：
1. 收水（独立逻辑 2）
2. 收岩浆（独立逻辑 2）
3. 手持物品匹配
4. 蜘蛛网覆盖为岩浆
5. 放蜘蛛网
6. 放岩浆

修改 `onTick` 中判断的顺序即可调整优先级。

### 15.3 调整延迟切回时间

修改 `placeBlock` / `scoopFluid` 中的：
```java
swapBackTimer = 2;  // 成功后的延迟
swapBackTimer = 1;  // 失败后的快速切回
```

---

## 16. 常见问题排查

| 问题 | 可能原因 | 解决 |
|------|----------|------|
| 放置没反应 | cd 冷却中 / aimed 为 null / 没有合法面 | 开 debug 看日志 |
| 被反作弊踢 | Silent Swap 或同一 tick 切回 | 已修复，使用物理切槽+延迟 |
| 松键没切回 | wasTriggered 状态异常 | 检查 onDeactivate 是否重置状态 |
| 瞄准水没收集 | 没有空桶 / hasInHotbar 返回 false | 检查热栏是否有桶 |
| 渲染不显示 | renderPos 为空 / 目标不在 range 内 | 检查 range 设置和目标距离 |
