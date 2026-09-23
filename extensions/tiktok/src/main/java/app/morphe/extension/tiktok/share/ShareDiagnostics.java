/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.share;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Share panel diagnostics for "Show all friends in share panel". Logs what each stage of the
 * panel's contact pipeline sees and decides, so a contact that stops appearing after a TikTok
 * update can be traced via "Export diagnostic report". Only active while "Enable diagnostic
 * logging" is on, and capped per session.
 */
public final class ShareDiagnostics {
    private static final String TAG = "[Morphe TikTok ShareProbe] ";
    private static final int MAX_EVENTS_PER_SESSION = 300;
    private static final AtomicInteger eventCount = new AtomicInteger();

    private ShareDiagnostics() {}

    /** The compact quick-share row's resolved candidate list (ShareContactListViewModel.QT2). */
    public static void logQuickRowCandidates(Object listObject) {
        if (!(listObject instanceof List<?>) || !shouldLog()) return;
        List<?> list = (List<?>) listObject;

        log("quick-row candidates size=" + list.size());
        for (Object contact : list) {
            if (!shouldLog()) return;
            log("quick-row candidate " + describe(contact));
        }
    }

    /** The share panel's SQL contact query clause (X/0epp;), before and after relaxing it. */
    public static void logQueryRewrite(String original, String relaxed) {
        if (!shouldLog()) return;
        log("db-query where: \"" + original + "\" -> \"" + relaxed + "\"");
    }

    /** Entry to the search box's server recheck (X/0oT1;), which only runs with a typed query. */
    public static void logSearchRecheckEntry(Object recheck, Object listObject) {
        if (!shouldLog()) return;
        int size = listObject instanceof List<?> ? ((List<?>) listObject).size() : -1;
        log("search-recheck query=" + field(recheck, "LIZ") + " listSize=" + size);
    }

    public static void logLegacyFilterDecision(Object contact, boolean included) {
        logDecision("string-filter(oSL)", contact, included);
    }

    public static void logBaseFilterDecision(Object contact, boolean included) {
        logDecision("base-filter(oS7)", contact, included);
    }

    public static void logPrivacyRestrictedFilterDecision(Object contact, boolean included) {
        logDecision("privacy-restricted(oSN)", contact, included);
    }

    public static void logQuickRowFilterDecision(Object contact, boolean included) {
        logDecision("quick-row-filter(oS8)", contact, included);
    }

    public static void logQuickRowStatsFilterDecision(Object contact, boolean included) {
        logDecision("quick-row-filter(oS5)", contact, included);
    }

    private static void logDecision(String source, Object contact, boolean included) {
        if (contact == null || !shouldLog()) return;
        log(source + " " + describe(contact) + " included=" + included);
    }

    /** Raw getter values, i.e. what TikTok stored - not the values the patch normalizes to. */
    private static String describe(Object contact) {
        if (contact == null) return "null";
        String className = contact.getClass().getName();
        if (className.contains("IMConversation")) {
            return "conversation id=" + invoke(contact, "getConversationId")
                    + " members=" + invoke(contact, "getConversationMemberCount");
        }
        return "uid=" + invoke(contact, "getUid")
                + " uniqueId=" + invoke(contact, "getUniqueId")
                + " followStatus=" + invoke(contact, "getFollowStatus")
                + " followerStatus=" + invoke(contact, "getFollowerStatus")
                + " shareStatus=" + invoke(contact, "getShareStatus")
                + " isBlock=" + invoke(contact, "isBlock");
    }

    private static boolean shouldLog() {
        try {
            return BaseSettings.DEBUG.get() && eventCount.incrementAndGet() <= MAX_EVENTS_PER_SESSION;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void log(String message) {
        try {
            Logger.printInfo(() -> TAG + message);
        } catch (Exception ignored) {
        }
    }

    private static Object invoke(Object target, String methodName) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (Exception ignored) {
            }
        }
        return "n/a";
    }

    private static Object field(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception ignored) {
            return "?";
        }
    }
}
