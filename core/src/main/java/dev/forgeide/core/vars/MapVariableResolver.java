package dev.forgeide.core.vars;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link VariableResolver} backed by an in-memory map keyed by {@code "scope.key"}.
 * The engine builds one per run from the project config, the feature slug/params and the
 * user-supplied param values.
 */
public final class MapVariableResolver implements VariableResolver {

    private final Map<String, String> values;

    private MapVariableResolver(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Optional<String> resolve(VariableReference ref) {
        return Optional.ofNullable(values.get(ref.scope() + "." + ref.key()));
    }

    public static final class Builder {
        private final Map<String, String> values = new LinkedHashMap<>();

        public Builder put(String scope, String key, String value) {
            Objects.requireNonNull(value, "value");
            values.put(scope + "." + key, value);
            return this;
        }

        public Builder project(String key, String value) {
            return put("project", key, value);
        }

        public Builder feature(String key, String value) {
            return put("feature", key, value);
        }

        public Builder param(String key, String value) {
            return put("params", key, value);
        }

        public MapVariableResolver build() {
            return new MapVariableResolver(values);
        }
    }
}
