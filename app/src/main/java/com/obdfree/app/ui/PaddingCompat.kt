package com.obdfree.app.ui

import androidx.compose.foundation.layout.padding as composePadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Small layout helper used for the asymmetric section-header spacing.
 */
internal fun Modifier.padding(horizontal: Dp, top: Dp): Modifier =
    this.composePadding(
        start = horizontal,
        top = top,
        end = horizontal,
    )
