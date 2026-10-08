package com.pocketdisco.playback

import com.facebook.react.common.LifecycleState

internal fun isPlaybackForeground(state: LifecycleState, invalidated: Boolean): Boolean =
    !invalidated && state == LifecycleState.RESUMED
