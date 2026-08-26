package pl.piekoszek.prompter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import pl.piekoszek.prompter.ui.EditorScreen
import pl.piekoszek.prompter.ui.PrompterScreen
import pl.piekoszek.prompter.ui.SettingsScreen
import pl.piekoszek.prompter.ui.theme.PrompterTheme

private enum class Screen { EDITOR, PROMPTER, SETTINGS }

/**
 * Single-activity host with plain enum navigation (PROJEKT 6/7):
 * EDITOR ↔ PROMPTER, EDITOR ↔ SETTINGS. No navigation-compose.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val vm: PrompterViewModel = ViewModelProvider(this)[PrompterViewModel::class.java]

        // Ask for the microphone once, up front; the VM re-checks on start.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result re-checked by the VM on start */ }
                .launch(Manifest.permission.RECORD_AUDIO)
        }

        var screen by mutableStateOf(Screen.EDITOR)
        fun backToEditor() {
            if (screen == Screen.PROMPTER) vm.stopSession()
            screen = Screen.EDITOR
        }

        setContent {
            PrompterTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        Screen.EDITOR -> EditorScreen(
                            vm = vm,
                            onOpenScript = { screen = Screen.PROMPTER },
                            onSettings = { screen = Screen.SETTINGS },
                        )
                        Screen.PROMPTER -> PrompterScreen(
                            vm = vm,
                            onBack = { backToEditor() },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            vm = vm,
                            onBack = { screen = Screen.EDITOR },
                        )
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (screen != Screen.EDITOR) backToEditor() else finish()
            }
        })
    }
}
