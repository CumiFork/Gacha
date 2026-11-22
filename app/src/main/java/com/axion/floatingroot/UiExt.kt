package com.axion.floatingroot

import android.content.Context

internal fun Int.dp(context: Context): Int = (this * context.resources.displayMetrics.density).toInt()
