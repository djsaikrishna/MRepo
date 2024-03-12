package com.sanmer.mrepo.ui.activity

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.sanmer.mrepo.app.utils.MediaStoreUtils
import com.sanmer.mrepo.datastore.isDarkMode
import com.sanmer.mrepo.provider.ProviderCompat
import com.sanmer.mrepo.repository.UserPreferencesRepository
import com.sanmer.mrepo.ui.providable.LocalUserPreferences
import com.sanmer.mrepo.ui.theme.AppTheme
import com.sanmer.mrepo.utils.extensions.tmpDir
import com.sanmer.mrepo.viewmodel.InstallViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class InstallActivity : ComponentActivity() {
    @Inject lateinit var userPreferencesRepository: UserPreferencesRepository
    private val viewModule: InstallViewModel by viewModels()

    private var isReady by mutableStateOf(false)
    private var zipPath: String = ""
    private var isReal = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Timber.d("InstallActivity onCreate")
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent.data == null) {
            finish()
        }

        setContent {
            val userPreferences by userPreferencesRepository.data
                .collectAsStateWithLifecycle(initialValue = null)

            val preferences = if (userPreferences == null) {
                return@setContent
            } else {
                checkNotNull(userPreferences)
            }

            LaunchedEffect(userPreferences) {
                ProviderCompat.init(preferences.workingMode)
            }

            LaunchedEffect(ProviderCompat.isAlive) {
                if (ProviderCompat.isAlive) {
                    initModule(intent)
                }
            }

            LaunchedEffect(isReady) {
                if (isReady) {
                    viewModule.install(zipPath = zipPath, isReal = isReal)
                }
            }

            CompositionLocalProvider(
                LocalUserPreferences provides preferences
            ) {
                AppTheme(
                    darkMode = preferences.isDarkMode(),
                    themeColor = preferences.themeColor
                ) {
                    InstallScreen()
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.d("InstallActivity onDestroy")
        tmpDir.deleteRecursively()
        super.onDestroy()
    }

    private fun initModule(intent: Intent) = lifecycleScope.launch {
        val zipUri = checkNotNull(intent.data)

        withContext(Dispatchers.IO) {
            val path = runCatching {
                MediaStoreUtils.getAbsolutePathForUri(
                    context = this@InstallActivity,
                    uri = zipUri
                )
            }.getOrDefault("")

            ProviderCompat.moduleManager
                .getModuleInfo(path)?.let {
                    zipPath = path
                    isReady = true
                    isReal = true

                    Timber.d("module = $it")
                }

            if (isReady) return@withContext

            val tmpFile = tmpDir.resolve("tmp.zip")
            contentResolver.openInputStream(zipUri)?.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            ProviderCompat.moduleManager
                .getModuleInfo(zipPath)?.let {
                    zipPath = tmpFile.path
                    isReady = true

                    Timber.d("module = $it")
                }

            if (!isReady) finish()
        }
    }

    companion object {
        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, InstallActivity::class.java)
                .apply {
                    data = uri
                }

            context.startActivity(intent)
        }
    }
}