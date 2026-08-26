package pl.piekoszek.prompter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.piekoszek.prompter.PrompterViewModel

/**
 * Screen 3 (PROJEKT 6): font, background, alignment thresholds, partial follow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: PrompterViewModel,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val s = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ustawienia") },
                navigationIcon = {
                    Button(onClick = onBack) { Text("←") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Rozmiar czcionki: ${s.fontSizeSp} sp",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Slider(
                value = s.fontSizeSp.toFloat(),
                onValueChange = { vm.setFontSize(it.toInt()) },
                valueRange = PrompterViewModel.MIN_FONT_SP.toFloat()..PrompterViewModel.MAX_FONT_SP.toFloat(),
            )

            Spacer(modifier = Modifier.height(16.dp))
            RowToggle(
                label = if (s.darkBackground) "Tło: czarne" else "Tło: białe",
                onToggle = { vm.toggleBackground() },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Podążaj za częściowym (partial)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Podświetlanie i pozycja podglądowa reagują na żywy fragment, zanim zdanie się zamknie.",
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(
                checked = s.followPartial,
                onCheckedChange = { vm.setFollowPartial(it) },
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Próg pewności słowa: ${"%.2f".format(s.confThreshold)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Słowa z niższą pewnością nie liczą się w dopasowaniu.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = s.confThreshold,
                onValueChange = { vm.setConfThreshold(it) },
                valueRange = 0f..1f,
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Histeresja (margin): ${"%.2f".format(s.margin)}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Więcej = stabilniejsza pozycja (wolniej podąża); mniej = szybsza reakcja, ale więcej skoków.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = s.margin,
                onValueChange = { vm.setMargin(it) },
                valueRange = 0.05f..0.5f,
            )
        }
    }
}

@Composable
private fun RowToggle(label: String, onToggle: () -> Unit) {
    Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Text(label)
    }
}
