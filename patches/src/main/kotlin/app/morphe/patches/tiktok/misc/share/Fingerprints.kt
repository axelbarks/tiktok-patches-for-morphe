/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/share/Fingerprints.kt
 */
package app.morphe.patches.tiktok.misc.share

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object ShareUrlTrackerFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("utm_campaign", "share_link_id"),
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.count { it == "Ljava/lang/String;" } >= 2
    },
)

// Matches the per-contact filter the share/"Send to" panel runs while building its contact
// list. It drops a contact when an A/B-gated follow-status cache check fails, or when the
// contact's server-supplied shareStatus flag is 2, independently of whether the contact can
// actually be messaged, which is what causes some mutuals and private-profile friends to
// silently disappear from the panel.
internal object ShareTargetFilterFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMContact;"),
    strings = listOf("user has been filter "),
)

// The base contact filter shared by every share panel scene (X/0oS7;->LIZ in 46.2.3). Beyond
// the string-matched filter above, it independently excludes an IMUser whose cached
// followStatus isn't 2 (mutual) whenever the current scene requires mutual follows, which is a
// second, broader gate a stale follow-status cache can trip.
internal object ShareContactBaseFilterFingerprint : Fingerprint(
    definingClass = "LX/0oS7;",
    name = "LIZ",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMContact;"),
)

// A privacy-settings-gated wrapper around the base filter above (X/0oSN;->LIZ in 46.2.3). For
// a specific subset of contacts it skips the base filter entirely and instead excludes them
// solely based on shareStatus == 2.
internal object SharePrivacyRestrictedContactFilterFingerprint : Fingerprint(
    definingClass = "LX/0oSN;",
    name = "LIZ",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMContact;"),
)

// The three interceptors below (X/0oSA;, X/0oSC;, X/0oSD; in 46.2.3) each filter the "MAF"
// (mutual/active-friends) bucket of the share panel's combined contact loader by shareStatus
// before it's merged into the final list, independently of the filters above.
internal object ShareMafBucketFilterAFingerprint : Fingerprint(
    definingClass = "LX/0oSA;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
)

internal object ShareMafBucketFilterCFingerprint : Fingerprint(
    definingClass = "LX/0oSC;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
)

internal object ShareMafBucketFilterDFingerprint : Fingerprint(
    definingClass = "LX/0oSD;",
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "Ljava/lang/Object;"),
)

// When the share panel's search box has a non-empty query, X/0oS9;->LJIIJ delegates to this
// class (X/0oT1; in 46.2.3), which re-verifies the already-filtered contact list against the
// server via TikTokImApi.getSharePermissionForTTNContent - a live network round-trip, separate
// from and running after every filter fingerprinted above. A contact whose returned
// per-content shareStatus is 3 gets removed outright (shareStatus 4 just marks it disabled,
// without removing it).
internal object ShareSearchTtnPermissionFilterFingerprint : Fingerprint(
    definingClass = "LX/0oT1;",
    name = "LIZ",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/util/List;", "LX/01Ue;"),
)

// The share panel's DB-backed contact sources (X/0epp; in 46.2.3) hand a raw SQL WHERE clause
// (field LJII, one of the X/0UzK; constants such as "FOLLOW_STATUS == 2 and
// COLUMN_USER_SHARE_STATUS != 2") straight to IM_USER_BASE_INFO queries. That clause filters in
// SQLite itself, so a contact whose stored follow_status isn't 2 - or whose share status is 2 -
// is never loaded at all, and none of the Java-side getter normalizations above ever see them.
// LJ is the initial load (full weighted list, or first page), LJI is load-more paging. Only the
// share panel's own sources (X/0ekb;, X/0oS9;, X/0oSH;, X/0oSE;) ever write this field.
internal object ShareContactDbSourceLoadFingerprint : Fingerprint(
    definingClass = "LX/0epp;",
    name = "LJ",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("LX/01Ue;"),
    strings = listOf("loadInternal: "),
)

internal object ShareContactDbSourceLoadMoreFingerprint : Fingerprint(
    definingClass = "LX/0epp;",
    name = "LJI",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("LX/01Ue;"),
    strings = listOf("loadMoreInternal: "),
)

// The compact/quick-share row (internalshare's ShareContactListViewModel, via X/0oOE;) builds its
// candidates from a locally ranked list of recent shares, recent chats and followings, then runs
// each IMUser through its own per-user filters, which are independent of the legacy module's
// X/0oS7;/X/0oSL; above. Both drop a user when shareStatus == 2 or followStatus != 2 (each
// behind a scene-config flag), next to deliberate exclusions (exclude list, blocked users) that
// are left alone. X/0oS8;->LIZ is the default filter; X/0oS5;->LIZIZ is the variant used when
// the row's stats-reporting flag is on.
internal object ShareQuickRowUserFilterFingerprint : Fingerprint(
    definingClass = "LX/0oS8;",
    name = "LIZ",
    returnType = "Z",
    parameters = listOf("Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMUser;"),
)

internal object ShareQuickRowUserFilterWithStatsFingerprint : Fingerprint(
    definingClass = "LX/0oS5;",
    name = "LIZIZ",
    returnType = "Z",
    parameters = listOf(
        "Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMUser;",
        "LX/0oQc;",
        "LX/0oOJ;",
        "Ljava/lang/String;",
    ),
)

// The quick-share row's "filter_data" stage (X/0oOE;->LJJIFFI). After the per-user filters
// above, it skips a ranked user whose followStatus isn't 2 unless an existing conversation
// with them passes a separate check, gated by an experiment.
internal object ShareQuickRowFilterStageFingerprint : Fingerprint(
    definingClass = "LX/0oOE;",
    name = "LJJIFFI",
    returnType = "Ljava/util/List;",
    parameters = listOf("LX/0oOC;", "Z"),
    strings = listOf("start_filter_data"),
)

// Diagnostics only: ShareContactListViewModel.QT2 resolves the compact quick-share row's
// candidate list (from cache or the row builder). v5 holds that list right before the unique
// "streak_invite" check that starts local post-processing.
internal object ShareContactListFetchFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/internalshare/impl/refactor/vm/ShareContactListViewModel;",
    name = "QT2",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("LX/01Ue;"),
    strings = listOf("streak_invite"),
)
