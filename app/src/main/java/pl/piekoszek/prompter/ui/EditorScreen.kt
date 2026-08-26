package pl.piekoszek.prompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.piekoszek.prompter.PrompterViewModel
import pl.piekoszek.prompter.core.Normalizer

/**
 * Screen 1 (PROJEKT 6): write or load a script, manage the saved list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vm: PrompterViewModel,
    onOpenScript: () -> Unit,
    onSettings: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Prompter") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Ustawienia")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Tytuł") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Tekst") },
                minLines = 6,
                maxLines = 10,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Button(
                onClick = {
                    vm.saveScript(title, text)
                    title = ""
                    text = ""
                },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                Text("Zapisz skrypt")
            }

            Text(
                text = "Skrypty (${state.scripts.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
            )
            if (state.scripts.isEmpty()) {
                Text(
                    text = "Brak skryptów — wklej tekst powyżej i zapisz.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.weight(2f),
            ) {
                items(state.scripts, key = { it.id }) { script ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = script.title,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = "${Normalizer.words(script.text).size} słów",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = {
                            vm.openScript(script.id)
                            onOpenScript()
                        }) {
                            Text("Otwórz")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { vm.deleteScript(script.id) }) {
                            Text("Usuń")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
