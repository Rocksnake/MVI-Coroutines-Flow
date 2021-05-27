package com.hoc.flowmvi.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoc.flowmvi.domain.usecase.SearchUsersUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
internal class SearchVM(
  private val searchUsersUseCase: SearchUsersUseCase,
) : ViewModel() {
  private val _intentFlow = MutableSharedFlow<ViewIntent>(extraBufferCapacity = 64)
  private val _singleEvent = Channel<SingleEvent>(Channel.BUFFERED)

  val viewState: StateFlow<ViewState>
  val singleEvent: Flow<SingleEvent> get() = _singleEvent.receiveAsFlow()
  fun processIntent(intent: ViewIntent) = _intentFlow.tryEmit(intent)

  init {
    val initialVS = ViewState.initial()

    viewState = _intentFlow
      .toPartialStateChangesFlow()
      .sendSingleEvent()
      .scan(initialVS) { state, change -> change.reduce(state) }
      .catch { Log.d("###", "[SEARCH_VM] Throwable: $it") }
      .stateIn(viewModelScope, SharingStarted.Eagerly, initialVS)
  }

  private fun Flow<ViewIntent>.toPartialStateChangesFlow(): Flow<PartialStateChange> {
    val searchChange = filterIsInstance<ViewIntent.Search>()
      .debounce(Duration.milliseconds(400))
      .map { it.query }
      .filter { it.isNotBlank() }
      .distinctUntilChanged()
      .flatMapLatest { query ->
        flow { emit(searchUsersUseCase(query)) }
          .map {
            @Suppress("USELESS_CAST")
            PartialStateChange.Search.Success(
              it.map(UserItem::from),
              query,
            ) as PartialStateChange.Search
          }
          .onStart { emit(PartialStateChange.Search.Loading) }
          .catch { emit(PartialStateChange.Search.Failure(it, query)) }
      }

    return merge(
      searchChange
    )
  }

  private fun Flow<PartialStateChange>.sendSingleEvent(): Flow<PartialStateChange> =
    onEach { change ->
      when (change) {
        is PartialStateChange.Search.Failure -> _singleEvent.send(SingleEvent.SearchFailure(change.error))
        PartialStateChange.Search.Loading -> return@onEach
        is PartialStateChange.Search.Success -> return@onEach
      }
    }
}
