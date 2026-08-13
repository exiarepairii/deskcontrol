package com.deskcontrol

/** Pure selection policy for external displays, kept separate for deterministic JVM tests. */
internal object ExternalDisplaySelector {
    data class Candidate(
        val displayId: Int,
        val valid: Boolean,
        val stateOff: Boolean,
        val stateOn: Boolean,
        val trusted: Boolean,
        val standardActivityAllowed: Boolean?,
        val allowEmbeddedActivityAllowed: Boolean?,
        val mediaRouteMatch: Boolean,
        val presentation: Boolean
    )

    enum class RejectionReason {
        INVALID,
        SUSPENDED_UNTRUSTED_POLICY_DENIED,
        POLICY_DENIED_WHEN_ALLOWED_DISPLAY_EXISTS
    }

    enum class SelectionReason {
        NO_SELECTABLE_DISPLAY,
        FIRST_SELECTABLE_DISPLAY,
        KEEP_CURRENT_DISPLAY,
        CURRENT_DISPLAY_REMOVED,
        CURRENT_DISPLAY_INVALID,
        CURRENT_DISPLAY_UNAVAILABLE,
        BETTER_DISPLAY_AVAILABLE
    }

    data class Decision(
        val selectedId: Int?,
        val selectableIds: List<Int>,
        val rejected: Map<Int, RejectionReason>,
        val reason: SelectionReason
    )

    fun decide(
        candidates: List<Candidate>,
        currentSelectedId: Int?,
        preferCurrentSelection: Boolean = false
    ): Decision {
        val rejected = linkedMapOf<Int, RejectionReason>()
        val usableCandidates = candidates.filter { candidate ->
            when {
                !candidate.valid -> {
                    rejected[candidate.displayId] = RejectionReason.INVALID
                    false
                }

                !candidate.stateOn &&
                    !candidate.trusted &&
                    candidate.standardActivityAllowed == false &&
                    candidate.allowEmbeddedActivityAllowed == false -> {
                    rejected[candidate.displayId] =
                        RejectionReason.SUSPENDED_UNTRUSTED_POLICY_DENIED
                    false
                }

                else -> true
            }
        }

        val hasConfirmedAllowedDisplay = usableCandidates.any {
            it.standardActivityAllowed == true
        }
        val selectableCandidates = usableCandidates.filter { candidate ->
            if (hasConfirmedAllowedDisplay && candidate.standardActivityAllowed == false) {
                rejected[candidate.displayId] =
                    RejectionReason.POLICY_DENIED_WHEN_ALLOWED_DISPLAY_EXISTS
                false
            } else {
                true
            }
        }.sortedWith(
            compareByDescending<Candidate> { policyRank(it) }
                .thenByDescending { it.stateOn }
                .thenByDescending { it.mediaRouteMatch }
                .thenByDescending { it.presentation }
                .thenByDescending { it.trusted }
                .thenBy { it.displayId }
        )

        if (selectableCandidates.isEmpty()) {
            return Decision(
                selectedId = null,
                selectableIds = emptyList(),
                rejected = rejected,
                reason = SelectionReason.NO_SELECTABLE_DISPLAY
            )
        }

        val currentCandidate = selectableCandidates.firstOrNull {
            it.displayId == currentSelectedId
        }
        val bestCandidate = selectableCandidates.first()
        val keepCurrent = currentCandidate != null && (
            preferCurrentSelection || selectionTier(currentCandidate) == selectionTier(bestCandidate)
        )
        val selectedId = if (keepCurrent) {
            requireNotNull(currentCandidate).displayId
        } else {
            selectableCandidates.first().displayId
        }
        val reason = when {
            keepCurrent -> SelectionReason.KEEP_CURRENT_DISPLAY
            currentSelectedId == null -> SelectionReason.FIRST_SELECTABLE_DISPLAY
            candidates.none { it.displayId == currentSelectedId } ->
                SelectionReason.CURRENT_DISPLAY_REMOVED
            rejected[currentSelectedId] == RejectionReason.INVALID ->
                SelectionReason.CURRENT_DISPLAY_INVALID
            rejected[currentSelectedId] ==
                RejectionReason.SUSPENDED_UNTRUSTED_POLICY_DENIED ->
                SelectionReason.CURRENT_DISPLAY_UNAVAILABLE
            else -> SelectionReason.BETTER_DISPLAY_AVAILABLE
        }

        return Decision(
            selectedId = selectedId,
            selectableIds = selectableCandidates.map { it.displayId },
            rejected = rejected,
            reason = reason
        )
    }

    private fun policyRank(candidate: Candidate): Int = when {
        candidate.standardActivityAllowed == true -> 3
        candidate.allowEmbeddedActivityAllowed == true -> 2
        candidate.standardActivityAllowed == null -> 1
        else -> 0
    }

    private fun selectionTier(candidate: Candidate): Int =
        policyRank(candidate) * 2 + if (candidate.stateOn) 1 else 0
}
