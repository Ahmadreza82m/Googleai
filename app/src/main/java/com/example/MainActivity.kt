package com.example

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.ZipMasterTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.util.LocaleManager
import com.example.util.ZipMasterLocaleProvider

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleManager.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleManager.applyLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val selectedLanguage by mainViewModel.selectedLanguage.collectAsStateWithLifecycle()

            ZipMasterLocaleProvider(langCode = selectedLanguage) {
                ZipMasterTheme {
                    HomeScreen(viewModel = mainViewModel)
                }
            }
        }
    }
}
