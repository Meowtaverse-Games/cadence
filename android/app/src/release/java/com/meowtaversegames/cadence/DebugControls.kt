package com.meowtaversegames.cadence

import androidx.compose.runtime.Composable

/** No test timetable UI or generator is included in release builds. */
@Composable
fun DebugControls(runtime: BlockingRuntime, onError: (String) -> Unit) = Unit
