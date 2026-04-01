package com.airijko.endlessleveling.compatibility;

/**
 * Optional bridge for Endless Leveling API.
 */
public final class EndlessLevelingCompatibility {

	private static final String API_CLASS = "com.airijko.endlessleveling.api.EndlessLevelingAPI";
    private static final String GATES_MANAGER_KEY = "gates";

	private EndlessLevelingCompatibility() {
	}

    public static boolean isAvailable() {
        return getApiInstance() != null;
    }

    public static Object getApiInstance() {
        try {
            return Class.forName(API_CLASS).getMethod("get").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean registerGatesManager(Object manager) {
        if (manager == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("registerManager", String.class, Object.class, boolean.class)
                    .invoke(api, GATES_MANAGER_KEY, manager, true);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean unregisterGatesManager(Object manager) {
        if (manager == null) {
            return false;
        }

        Object api = getApiInstance();
        if (api == null) {
            return false;
        }

        try {
            return (boolean) api.getClass()
                    .getMethod("unregisterManager", String.class, Object.class)
                    .invoke(api, GATES_MANAGER_KEY, manager);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
