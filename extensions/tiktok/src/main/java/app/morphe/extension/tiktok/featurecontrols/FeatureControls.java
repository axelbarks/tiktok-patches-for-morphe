/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featurecontrols;

import android.app.Activity;
import android.content.Intent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.share.ShareDiagnostics;

public final class FeatureControls {
    private static final int DEFAULT_LONG_PRESS_LOCK_DISTANCE_DP = 140;
    private static final String ACCOUNT_ACTIVITY_PREFIX = "com.ss.android.ugc.aweme.account.";
    private static final String SERVICE_MANAGER_CLASS =
            "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String ACCOUNT_USER_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.IAccountUserService";

    private FeatureControls() {
    }

    public static boolean shouldHideCaptchaPopup() {
        return Settings.HIDE_CAPTCHA_POPUPS.get() && isLoggedIn();
    }

    public static boolean shouldHideCaptchaPopup(Activity activity) {
        return shouldHideCaptchaPopup(activity, null);
    }

    public static boolean shouldHideCaptchaPopup(Activity activity, String riskInfo) {
        if (!Settings.HIDE_CAPTCHA_POPUPS.get() || !isLoggedIn()) return false;
        if (activity == null) return !isAccountRoute(riskInfo);

        // Account flows must be able to present server-required verification.
        if (activity.getClass().getName().startsWith(ACCOUNT_ACTIVITY_PREFIX)) return false;

        Intent intent = activity.getIntent();
        String intentRoute = intent == null ? null : intent.getDataString();
        return !isAccountRoute(intentRoute) && !isAccountRoute(riskInfo);
    }

    public static boolean shouldHideCaptchaPopup(Activity activity, Object verifyRequest) {
        if (!Settings.HIDE_CAPTCHA_POPUPS.get() || !isLoggedIn()) return false;
        if (activity != null && activity.getClass().getName().startsWith(ACCOUNT_ACTIVITY_PREFIX)) {
            return false;
        }

        String scene = readVerificationScene(verifyRequest);
        if (scene == null) return false;

        Intent intent = activity == null ? null : activity.getIntent();
        String intentRoute = intent == null ? null : intent.getDataString();
        return !isAccountRoute(intentRoute) && !isAccountRoute(scene);
    }

    private static String readVerificationScene(Object verifyRequest) {
        if (verifyRequest == null) return null;
        try {
            Object value = verifyRequest.getClass().getMethod("LJIIJ").invoke(verifyRequest);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isLoggedIn() {
        try {
            Class<?> serviceManagerClass = Class.forName(SERVICE_MANAGER_CLASS);
            Object serviceManager = serviceManagerClass.getMethod("get").invoke(null);
            Class<?> accountServiceClass = Class.forName(ACCOUNT_USER_SERVICE_CLASS);
            Object accountService = serviceManagerClass
                    .getMethod("getService", Class.class)
                    .invoke(serviceManager, accountServiceClass);
            return accountService != null
                    && Boolean.TRUE.equals(accountServiceClass.getMethod("isLogin").invoke(accountService));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isAccountRoute(String value) {
        if (value == null) return false;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("login")
                || normalized.equals("passport")
                || normalized.contains("/passport/")
                || normalized.contains("/login/")
                || normalized.contains("\"passport\"")
                || normalized.contains("\"login\"");
    }

    public static boolean shouldShowAllShareTargets() {
        return Settings.SHOW_ALL_SHARE_TARGETS.get();
    }

    /** Mutual (2) satisfies every follow-based share panel gate without a stale-cache miss. */
    public static int normalizeFollowStatusForSharePanel(int followStatus) {
        return shouldShowAllShareTargets() ? 2 : followStatus;
    }

    /** Explicitly-allowed (1) satisfies both the "== 1" and "!= 2" share panel gates. */
    public static int normalizeShareStatusForSharePanel(int shareStatus) {
        return shouldShowAllShareTargets() ? 1 : shareStatus;
    }

    /** A very large cap effectively disables the share panel's fixed-size recent-chat cutoff. */
    public static int normalizeRecentShareLimit(int limit) {
        return shouldShowAllShareTargets() ? Integer.MAX_VALUE : limit;
    }

    /** Scene type 3 satisfies the check that otherwise hides every recent-chat conversation. */
    public static int normalizeShareSceneTypeForConversations(int sceneType) {
        return shouldShowAllShareTargets() ? 3 : sceneType;
    }

    private static final Pattern SHARE_STATUS_SQL_CLAUSE = Pattern.compile(
            "\\s+and\\s+COLUMN_USER_SHARE_STATUS\\s*!=\\s*2\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern FOLLOW_STATUS_SQL_CLAUSE = Pattern.compile(
            "(?<![\\w.])FOLLOW_STATUS\\s*(?:==|=|!=)\\s*\\d+", Pattern.CASE_INSENSITIVE);

    private static final String ANY_FOLLOW_RELATION_SQL_CLAUSE =
            "(IM_USER_BASE_INFO.FOLLOW_STATUS != 0 OR IM_USER_BASE_INFO.FOLLOWER_STATUS != 0)";

    /**
     * The share panel's friends list is loaded from IM_USER_BASE_INFO with a raw SQL WHERE clause
     * (e.g. "FOLLOW_STATUS == 2 and COLUMN_USER_SHARE_STATUS != 2") that filters in SQLite, before
     * any Java-side filter runs. Drop the share-status condition and widen the follow condition to
     * any follow relationship in either direction. Everything else in the clause (e.g. an appended
     * "or UID = <self>") and the query's own DELETED / exclude-list conditions are left intact.
     */
    public static String relaxShareContactQuery(String where) {
        if (where == null || !shouldShowAllShareTargets()) {
            return where;
        }

        try {
            String relaxed = SHARE_STATUS_SQL_CLAUSE.matcher(where).replaceAll("");
            relaxed = FOLLOW_STATUS_SQL_CLAUSE.matcher(relaxed)
                    .replaceAll(Matcher.quoteReplacement(ANY_FOLLOW_RELATION_SQL_CLAUSE));
            ShareDiagnostics.logQueryRewrite(where, relaxed);
            return relaxed;
        } catch (Exception ex) {
            return where;
        }
    }

    /**
     * The share panel's search box triggers a live server round-trip re-verifying the already-
     * filtered contact list (TikTokImApi.getSharePermissionForTTNContent), after every other
     * filter has already passed a contact. A per-content secUid coming back in one response set
     * strips the contact from the list outright (Iterator.remove()); coming back in the other
     * just marks it disabled (setDisabledOnSharePanelReasonCode) without removing it. Both
     * containment checks route through here and get forced to false, so neither the removal nor
     * the disabled marking happens - the actual send can still be rejected server-side, which is
     * an acceptable fallback.
     */
    public static boolean normalizeTtnRemovalCheck(boolean matchedRestrictionSet) {
        return !shouldShowAllShareTargets() && matchedRestrictionSet;
    }

    public static Object filterNormalPendant(Object pendant) {
        return filterPromotionalTouchPoint(pendant);
    }

    public static Object filterPromotionalTouchPoint(Object touchPoint) {
        if (!Settings.HIDE_HOMEPAGE_COIN.get() || touchPoint == null) return touchPoint;

        String className = touchPoint.getClass().getName();
        switch (className) {
            case "com.bytedance.touchpoint.api.model.NormalPendant":
            case "com.bytedance.touchpoint.api.model.TimerPendant":
            case "com.bytedance.touchpoint.api.model.SunshinePendant":
            case "com.bytedance.touchpoint.api.model.CoinBottomTab":
            case "com.bytedance.touchpoint.api.model.BottomTabBubble":
                return null;
            default:
                return touchPoint;
        }
    }

    public static boolean overrideLongPressSpeedUpEnabled(boolean enabled) {
        return Settings.ENABLE_LONG_PRESS_SPEED_LOCK.get() || enabled;
    }

    public static int overrideLongPressSpeedUpLockDistance(int distanceDp) {
        if (!Settings.ENABLE_LONG_PRESS_SPEED_LOCK.get()) return distanceDp;
        return distanceDp > 0 ? distanceDp : DEFAULT_LONG_PRESS_LOCK_DISTANCE_DP;
    }

    public static boolean overrideHideQuickCommentEmoji(boolean original, int followStatus) {
        return Settings.HIDE_COMMENT_QUICK_REACTIONS.get() || original;
    }

    public static int overrideLongPressQuickShare(int originalMode) {
        return Settings.DISABLE_LONG_PRESS_QUICK_SHARE.get() ? 0 : originalMode;
    }

    public static boolean disableLongPressRepost() {
        return Settings.DISABLE_LONG_PRESS_REPOST.get();
    }

    public static boolean enableNonPersonalizedSearch(boolean original) {
        return Settings.ENABLE_NON_PERSONALIZED_SEARCH.get() || original;
    }

    public static int forceNonPersonalizedSearchGate(String key, int value) {
        if (!"enable_non_personalized_search".equals(key)) return value;
        return Settings.ENABLE_NON_PERSONALIZED_SEARCH.get() ? 1 : value;
    }

    public static boolean enableLiveSearch(boolean original) {
        return Settings.ENABLE_LIVE_SEARCH.get() || original;
    }
}
