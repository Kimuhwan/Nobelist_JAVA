package com.scientificlens.harness.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.scientificlens.harness.domain.LensProfile;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads the 15 lens profiles bundled as a classpath resource. */
public final class LensRegistry {
    private static final String RESOURCE = "/com/scientificlens/harness/lens_profiles.yaml";
    private static volatile List<LensProfile> cached;

    private LensRegistry() {}

    public static List<LensProfile> load() {
        List<LensProfile> profiles = cached;
        if (profiles == null) {
            profiles = read();
            cached = profiles;
        }
        return profiles;
    }

    public static LensProfile get(String lensId) {
        return load().stream()
                .filter(p -> p.lensId().equals(lensId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown lens id: " + lensId));
    }

    private static List<LensProfile> read() {
        try (InputStream in = LensRegistry.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            List<LensProfile> profiles = Json.YAML.readValue(in, new TypeReference<List<LensProfile>>() {});
            Set<String> seen = new HashSet<>();
            for (LensProfile profile : profiles) {
                if (!seen.add(profile.lensId())) {
                    throw new IllegalStateException("duplicate lens id: " + profile.lensId());
                }
            }
            return List.copyOf(profiles);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load lens profiles", e);
        }
    }
}
