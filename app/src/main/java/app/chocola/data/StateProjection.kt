package app.chocola.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn

/** A screen owns its subscription; its first value is already populated before collection. */
@OptIn(FlowPreview::class)
internal fun <T, R> StateFlow<T>.project(owner: CoroutineScope, periodMillis: Long = 0, eager: Boolean = false, select: (T) -> R): StateFlow<R> {
    val selected = map { select(it) }.distinctUntilChanged()
    val updates = if (periodMillis > 0) selected.sample(periodMillis) else selected
    return updates.stateIn(owner, if (eager) SharingStarted.Eagerly else SharingStarted.WhileSubscribed(), select(value))
}
