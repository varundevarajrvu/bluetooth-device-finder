package app.locate.ui.alertlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.locate.data.db.EpisodeWithNickname
import app.locate.data.repo.AlertEpisodeRepository
import app.locate.domain.alert.EpisodeState
import app.locate.ui.common.BackTopBar
import app.locate.ui.common.InfoCard
import app.locate.ui.common.clockTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class AlertLogViewModel @Inject constructor(repo: AlertEpisodeRepository) : ViewModel() {
    val log: StateFlow<List<EpisodeWithNickname>> = repo.observeLog().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

/** Every disconnect and what Locate decided about it. This is the tool for tuning false alarms. */
@Composable
fun AlertLogScreen(onBack: () -> Unit, vm: AlertLogViewModel = hiltViewModel()) {
    val log by vm.log.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Alert log", onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { InfoCard("Every disconnect of a watched device, and why Locate did or didn't alert you.") }
            if (log.isEmpty()) item { Text("Nothing yet.") }
            items(log, key = { it.episode.id }) { row ->
                val e = row.episode
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${row.nickname} · ${label(e.state)}", style = MaterialTheme.typography.titleSmall)
                        Text(e.reason, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${clockTime(e.startedAt)}" +
                                (e.anchorAccuracyM?.let { " · anchor ±${it.roundToInt()} m" } ?: "") +
                                (if (e.maxSeparationM > 0) " · max ${e.maxSeparationM.roundToInt()} m away" else ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun label(state: EpisodeState) = when (state) {
    EpisodeState.PENDING -> "Watching"
    EpisodeState.ALERTED -> "Alerted"
    EpisodeState.CANCELLED -> "Came back"
    EpisodeState.SUPPRESSED -> "No alert"
    EpisodeState.EXPIRED -> "No alert"
    EpisodeState.DISMISSED -> "Dismissed"
}
