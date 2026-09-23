/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.share

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private const val SHARE_DIAGNOSTICS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/share/ShareDiagnostics;"

@Suppress("unused")
val showAllShareTargetsPatch = bytecodePatch(
    name = "Show all friends in share panel",
    description = "Shows everyone you follow or who follows you in the share panel, including " +
        "contacts TikTok hides based on follow or share status.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableShowAllShareTargets()V",
        )

        // Expanded panel / search: the contact list is loaded from IM_USER_BASE_INFO with a raw
        // SQL WHERE clause (FOLLOW_STATUS == 2, COLUMN_USER_SHARE_STATUS != 2) applied in SQLite,
        // so a friend with a broken follow-back is never loaded at all. Rewrite the clause.
        listOf(
            ShareContactDbSourceLoadFingerprint.method,
            ShareContactDbSourceLoadMoreFingerprint.method,
        ).forEach { method ->
            method.normalizeObjectFieldReadInPlace(
                fieldName = "LJII",
                fieldDefiningClassSuffix = "/0epp;",
                fieldType = "Ljava/lang/String;",
                normalizerName = "relaxShareContactQuery",
            )
        }

        // Expanded panel: per-contact filters applied to the loaded list.
        ShareTargetFilterFingerprint.method.apply {
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $FEATURE_CONTROLS_DESCRIPTOR->shouldShowAllShareTargets()Z
                    move-result v0
                    if-eqz v0, :morphe_apply_share_panel_filter
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("morphe_apply_share_panel_filter", getInstruction(0)),
            )
        }

        // The remaining gates read followStatus/shareStatus from a getter and branch on it.
        // Rewrite the value right after it's read: followStatus 2 (mutual) and shareStatus 1
        // (allowed) satisfy every "== 2", "== 1" and "!= 2" check. Other branches (blocked users,
        // exclude lists) are left untouched.
        ShareContactBaseFilterFingerprint.method.normalizeFollowStatus()

        listOf(
            SharePrivacyRestrictedContactFilterFingerprint.method,
            ShareMafBucketFilterAFingerprint.method,
            ShareMafBucketFilterCFingerprint.method,
            ShareMafBucketFilterDFingerprint.method,
        ).forEach { it.normalizeShareStatus() }

        // Compact quick-share row: its own per-user filters and follow-status gate.
        listOf(
            ShareQuickRowUserFilterFingerprint.method,
            ShareQuickRowUserFilterWithStatsFingerprint.method,
        ).forEach {
            it.normalizeFollowStatus()
            it.normalizeShareStatus()
        }
        ShareQuickRowFilterStageFingerprint.method.normalizeFollowStatus()

        // Recent chats: the base filter drops every IMConversation unless the scene type is 3,
        // and the recent bucket is capped at 15 entries.
        ShareContactBaseFilterFingerprint.method.normalizeFieldReadInPlace(
            fieldName = "LIZ",
            fieldDefiningClassSuffix = "/0oQc;",
            normalizerName = "normalizeShareSceneTypeForConversations",
        )

        ShareMafBucketFilterAFingerprint.method.normalizeFieldReadInPlace(
            fieldName = "LJJIIJ",
            fieldDefiningClassSuffix = "/0oQc;",
            normalizerName = "normalizeRecentShareLimit",
        )

        // Search: with a query active, the list is re-verified against the server, which can
        // remove a contact (Iterator.remove()) or mark it disabled. Force both checks to false.
        ShareSearchTtnPermissionFilterFingerprint.method.apply {
            normalizeContainmentCheckBefore { instruction ->
                instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    instruction.getReference<MethodReference>()?.let {
                        it.name == "remove" && it.definingClass == "Ljava/util/Iterator;"
                    } == true
            }
            normalizeContainmentCheckBefore { instruction ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.getReference<MethodReference>()?.name == "setDisabledOnSharePanelReasonCode"
            }
        }

        // Diagnostics, inert unless "Enable diagnostic logging" is on. Added last so the filter
        // decision logs also cover the early return inserted into ShareTargetFilterFingerprint.
        ShareContactListFetchFingerprint.method.apply {
            addInstruction(
                indexOfFirstStringInstructionOrThrow("streak_invite"),
                "invoke-static {v5}, $SHARE_DIAGNOSTICS_DESCRIPTOR->logQuickRowCandidates(Ljava/lang/Object;)V",
            )
        }

        ShareSearchTtnPermissionFilterFingerprint.method.addInstruction(
            0,
            "invoke-static {p0, p1}, $SHARE_DIAGNOSTICS_DESCRIPTOR->logSearchRecheckEntry(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        ShareTargetFilterFingerprint.method.logEveryBooleanReturn("logLegacyFilterDecision")
        ShareContactBaseFilterFingerprint.method.logEveryBooleanReturn("logBaseFilterDecision")
        SharePrivacyRestrictedContactFilterFingerprint.method.logEveryBooleanReturn("logPrivacyRestrictedFilterDecision")
        ShareQuickRowUserFilterFingerprint.method.logEveryBooleanReturn("logQuickRowFilterDecision")
        ShareQuickRowUserFilterWithStatsFingerprint.method.logEveryBooleanReturn(
            "logQuickRowStatsFilterDecision",
            contactRegister = "p0",
        )
    }
}

/**
 * Logs the contact in [contactRegister] together with the value of every boolean `RETURN` in
 * this method via `ShareDiagnostics.<loggerMethodName>(Ljava/lang/Object;Z)V`.
 */
private fun MutableMethod.logEveryBooleanReturn(loggerMethodName: String, contactRegister: String = "p1") {
    val instructions = implementation!!.instructions

    instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN }
        .map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }
        .sortedByDescending { (index, _) -> index }
        .forEach { (index, register) ->
            addInstruction(
                index,
                "invoke-static {$contactRegister, v$register}, " +
                    "$SHARE_DIAGNOSTICS_DESCRIPTOR->$loggerMethodName(Ljava/lang/Object;Z)V",
            )
        }
}

private fun MutableMethod.normalizeFollowStatus() = normalizeGetterResultsInPlace(
    calleeName = "getFollowStatus",
    calleeDefiningClassSuffix = "/IMUser;",
    normalizerName = "normalizeFollowStatusForSharePanel",
)

private fun MutableMethod.normalizeShareStatus() = normalizeGetterResultsInPlace(
    calleeName = "getShareStatus",
    calleeDefiningClassSuffix = "/IMUser;",
    normalizerName = "normalizeShareStatusForSharePanel",
)

/**
 * Routes the int result of every `INVOKE_VIRTUAL` call to [calleeName] (declared on a class
 * ending with [calleeDefiningClassSuffix]) through `FeatureControls.<normalizerName>(I)I`.
 */
private fun MutableMethod.normalizeGetterResultsInPlace(
    calleeName: String,
    calleeDefiningClassSuffix: String,
    normalizerName: String,
) {
    val instructions = implementation!!.instructions

    val targets = instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                instruction.getReference<MethodReference>()?.let {
                    it.name == calleeName && it.definingClass.endsWith(calleeDefiningClassSuffix)
                } == true
        }
        .map { (index, _) ->
            val moveResultIndex = index + 1
            moveResultIndex to (instructions[moveResultIndex] as OneRegisterInstruction).registerA
        }
        .sortedByDescending { (index, _) -> index }

    targets.forEach { (moveResultIndex, register) ->
        addInstructions(
            moveResultIndex + 1,
            """
                invoke-static/range {v$register .. v$register}, $FEATURE_CONTROLS_DESCRIPTOR->$normalizerName(I)I
                move-result v$register
            """,
        )
    }
}

/**
 * Routes every `IGET` read of the int field [fieldName] (declared on a class ending with
 * [fieldDefiningClassSuffix]) through `FeatureControls.<normalizerName>(I)I`.
 */
private fun MutableMethod.normalizeFieldReadInPlace(
    fieldName: String,
    fieldDefiningClassSuffix: String,
    normalizerName: String,
) {
    val instructions = implementation!!.instructions

    val targets = instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.opcode == Opcode.IGET &&
                instruction.getReference<FieldReference>()?.let {
                    it.name == fieldName && it.definingClass.endsWith(fieldDefiningClassSuffix)
                } == true
        }
        .map { (index, instruction) ->
            index to (instruction as TwoRegisterInstruction).registerA
        }
        .sortedByDescending { (index, _) -> index }

    targets.forEach { (index, register) ->
        addInstructions(
            index + 1,
            """
                invoke-static/range {v$register .. v$register}, $FEATURE_CONTROLS_DESCRIPTOR->$normalizerName(I)I
                move-result v$register
            """,
        )
    }
}

/**
 * Routes every `IGET_OBJECT` read of the field [fieldName] (of type [fieldType], declared on a
 * class ending with [fieldDefiningClassSuffix]) through
 * `FeatureControls.<normalizerName>(fieldType)fieldType`.
 */
private fun MutableMethod.normalizeObjectFieldReadInPlace(
    fieldName: String,
    fieldDefiningClassSuffix: String,
    fieldType: String,
    normalizerName: String,
) {
    val instructions = implementation!!.instructions

    val targets = instructions.withIndex()
        .filter { (_, instruction) ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.let {
                    it.name == fieldName &&
                        it.type == fieldType &&
                        it.definingClass.endsWith(fieldDefiningClassSuffix)
                } == true
        }
        .map { (index, instruction) ->
            index to (instruction as TwoRegisterInstruction).registerA
        }
        .sortedByDescending { (index, _) -> index }

    check(targets.isNotEmpty()) { "No $fieldName reads found in $definingClass->$name" }

    targets.forEach { (index, register) ->
        addInstructions(
            index + 1,
            """
                invoke-static/range {v$register .. v$register}, $FEATURE_CONTROLS_DESCRIPTOR->$normalizerName($fieldType)$fieldType
                move-result-object v$register
            """,
        )
    }
}

/**
 * Finds the first instruction matching [anchorPredicate], walks back to the nearest preceding
 * `X/145U;->LJJJJ` containment check, and routes its boolean result through
 * `FeatureControls.normalizeTtnRemovalCheck(Z)Z`. Anchoring on a specific call is what targets
 * one use of a utility that the same method also calls for unrelated purposes.
 */
private fun MutableMethod.normalizeContainmentCheckBefore(anchorPredicate: (Instruction) -> Boolean) {
    val instructions = implementation!!.instructions

    val anchorIndex = instructions.indexOfFirst(anchorPredicate)
    check(anchorIndex >= 0) { "Anchor not found in $definingClass->$name" }

    val containmentCheckIndex = instructions.subList(0, anchorIndex).indexOfLast { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
            instruction.getReference<MethodReference>()?.let {
                it.name == "LJJJJ" && it.definingClass.endsWith("/145U;")
            } == true
    }
    check(containmentCheckIndex >= 0) { "Containment check not found in $definingClass->$name" }

    val moveResultIndex = containmentCheckIndex + 1
    val register = (instructions[moveResultIndex] as OneRegisterInstruction).registerA

    addInstructions(
        moveResultIndex + 1,
        """
            invoke-static/range {v$register .. v$register}, $FEATURE_CONTROLS_DESCRIPTOR->normalizeTtnRemovalCheck(Z)Z
            move-result v$register
        """,
    )
}
