package com.robomotion.app;
import java.util.ArrayList;
import java.util.List;

public class Capability {
    public static final long CapabilityCapnp = 1L << 0;
    private static final List<Long> capabilities = new ArrayList<Long>() {{
        add(CapabilityCapnp);
    }};

    public static void addCapability(long capability) {
        capabilities.add(capability);
    }

    public static void initCapabilities() {
        addCapability(CapabilityCapnp);
    }

    public static long getCapabilities() {
        long _capabilities = Long.MAX_VALUE;

        for (long cap : capabilities) {
            _capabilities &= cap;
        }
        return _capabilities;
    }
}
