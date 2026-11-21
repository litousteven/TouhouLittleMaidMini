package com.touhoulittlemaid.mini.core.plan;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The known modules, keyed by id.
 *
 * <p>A registry is immutable once built for a session. Adding a module is the only way to
 * widen what a plan can do, and that change is reviewed like any other Java code.
 */
public final class ModuleRegistry {

    private final Map<String, ActionModule> modules;

    private ModuleRegistry(Map<String, ActionModule> modules) {
        this.modules = Map.copyOf(modules);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ModuleRegistry empty() {
        return new ModuleRegistry(Map.of());
    }

    public Optional<ActionModule> find(String id) {
        return Optional.ofNullable(modules.get(id));
    }

    public ActionModule require(String id) {
        ActionModule module = modules.get(id);
        if (module == null) {
            throw new IllegalArgumentException("Unknown module: " + id);
        }
        return module;
    }

    public Collection<ActionModule> all() {
        return modules.values();
    }

    public List<String> ids() {
        return modules.keySet().stream().sorted().toList();
    }

    public int size() {
        return modules.size();
    }

    public static final class Builder {

        private final Map<String, ActionModule> modules = new LinkedHashMap<>();

        public Builder register(ActionModule module) {
            String id = module.id();
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("module id is required");
            }
            if (!id.equals(module.spec().id())) {
                throw new IllegalArgumentException(
                        "module id " + id + " does not match spec id " + module.spec().id());
            }
            ActionModule previous = modules.put(id, module);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate module id: " + id);
            }
            return this;
        }

        public ModuleRegistry build() {
            return new ModuleRegistry(modules);
        }
    }
}
