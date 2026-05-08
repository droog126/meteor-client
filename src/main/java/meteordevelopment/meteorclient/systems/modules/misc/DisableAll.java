/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.settings.ModuleListSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.TriggerBotV2;
import meteordevelopment.meteorclient.systems.modules.movement.UltimateSprint;
import meteordevelopment.meteorclient.systems.modules.combat.AutoWeb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DisableAll extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    
    private final Setting<List<Module>> modules = sgGeneral.add(new ModuleListSetting.Builder()
        .name("modules")
        .description("Modules to manage.")
        .build()
    );
    
    private final Setting<List<Module>> protectedModules = sgGeneral.add(new ModuleListSetting.Builder()
        .name("protected-modules")
        .description("Modules to never disable.")
        .build()
    );
    
    private final Map<Module, Boolean> savedStates = new HashMap<>();

    public DisableAll() {
        super(Categories.Misc, "disable-all", "Manages modules. When active, disables configured modules. When inactive, restores them.");
        showInModuleList = false;
    }

    @Override
    public void onActivate() {
        savedStates.clear();
        
        for (Module module : modules.get()) {
            if (module != null && module != this && !isProtected(module)) {
                savedStates.put(module, module.isActive());
                
                if (module.isActive()) {
                    boolean oldModuleFeedback = module.chatFeedback;
                    module.chatFeedback = false;
                    module.toggle();
                    module.chatFeedback = oldModuleFeedback;
                }
            }
        }
    }
    
    @Override
    public void onDeactivate() {
        for (Map.Entry<Module, Boolean> entry : savedStates.entrySet()) {
            Module module = entry.getKey();
            boolean wasActive = entry.getValue();
            
            if (module != null && module != this) {
                if (wasActive && !module.isActive()) {
                    boolean oldModuleFeedback = module.chatFeedback;
                    module.chatFeedback = false;
                    module.toggle();
                    module.chatFeedback = oldModuleFeedback;
                }
            }
        }
        savedStates.clear();
    }
    
    public boolean isProtected(Module module) {
        return module == this || protectedModules.get().contains(module);
    }
}
