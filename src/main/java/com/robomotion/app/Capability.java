package com.robomotion.app;

import java.util.Map;

public class Capability {
    public static final long CapabilityLMO = 1L << 0;
    private static long packageCapabilities = Long.MAX_VALUE;


    public static boolean isLMOCapable() throws RuntimeNotInitializedException {
        Map<String, Object> robotInfo = Runtime.GetRobotInfo(); // Assuming getRobotInfo() returns robotInfo as Map<String, Object> or null
        if (robotInfo == null) {
            return false;
        }

        Object capabilities = robotInfo.get("capabilities");
        if (capabilities instanceof Map) {
            Map<?, ?> robotCapabilities = (Map<?, ?>) capabilities;
            Object lmo = robotCapabilities.get("lmo");
            if (lmo instanceof Boolean) {
                return (Boolean) lmo;
            }
        }

        return false;
    }

    public static long getCapabilities() {
        return packageCapabilities;
    }

    public static void addCapability(long capability) {
        packageCapabilities &= capability;
    }

    public static void initCapabilities() {
        addCapability(CapabilityLMO);
    }

}
