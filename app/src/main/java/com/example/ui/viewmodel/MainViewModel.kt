package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.ActivityItem
import com.example.data.model.ActivityType
import com.example.data.model.OperationStatus
import com.example.data.repository.HistoryRepository
import com.example.engine.ArchiveEngine
import com.example.engine.ArchiveException
import com.example.engine.ArchiveMetadata
import com.example.engine.ProgressState
import com.example.util.LocaleManager
import com.example.util.StorageUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

sealed interface UiState {
    object Idle : UiState
    object Inspecting : UiState
    data class Inspected(val metadata: ArchiveMetadata) : UiState
    data class PasswordPrompt(val metadata: ArchiveMetadata, val isError: Boolean = false) : UiState
    data class Processing(val operationTitle: String, val progress: ProgressState) : UiState
    data class Success(val message: String, val details: String? = null) : UiState
    data class Error(val title: String, val description: String) : UiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: HistoryRepository
    val historyList: StateFlow<List<ActivityItem>>

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(LocaleManager.getSavedLanguage(application))
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private var activeJob: Job? = null
    private var currentInspectedUri: Uri? = null
    private var currentInspectedMetadata: ArchiveMetadata? = null

    init {
        val dao = AppDatabase.getDatabase(application).historyDao()
        repository = HistoryRepository(dao)
        historyList = repository.allActivities.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun setLanguage(langCode: String) {
        _selectedLanguage.value = langCode
        LocaleManager.saveLanguage(getApplication(), langCode)
    }

    fun onArchiveSelected(uri: Uri) {
        currentInspectedUri = uri
        _uiState.value = UiState.Inspecting
        viewModelScope.launch {
            try {
                val metadata = ArchiveEngine.inspectArchive(getApplication(), uri)
                currentInspectedMetadata = metadata
                _uiState.value = UiState.Inspected(metadata)
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = UiState.Error(
                    title = "خطا در خواندن فایل",
                    description = "فایل انتخاب‌شده قابل خواندن یا پردازش نیست."
                )
            }
        }
    }

    fun startExtraction(password: String? = null, customOutputDirName: String? = null) {
        val metadata = currentInspectedMetadata ?: return
        val uri = currentInspectedUri ?: return

        if (metadata.isEncrypted && password.isNullOrEmpty()) {
            _uiState.value = UiState.PasswordPrompt(metadata)
            return
        }

        val app = getApplication<Application>()
        val outputDirName = customOutputDirName ?: metadata.fileName.substringBeforeLast(".")
        val targetFolder = File(app.getExternalFilesDir(null) ?: app.filesDir, "Extracted/$outputDirName")

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = UiState.Processing(
                operationTitle = "در حال استخراج ${metadata.fileName}",
                progress = ProgressState(metadata.fileName, 0, metadata.entryCount, 0)
            )

            try {
                val extractedFiles = if (metadata.format == "RAR") {
                    ArchiveEngine.extractRar(app, uri, targetFolder, password) { progress ->
                        _uiState.value = UiState.Processing("در حال استخراج ${metadata.fileName}", progress)
                    }
                } else {
                    ArchiveEngine.extractZip(app, uri, targetFolder, password) { progress ->
                        _uiState.value = UiState.Processing("در حال استخراج ${metadata.fileName}", progress)
                    }
                }

                // Record history
                repository.addActivity(
                    ActivityItem(
                        fileName = metadata.fileName,
                        fileFormat = metadata.format,
                        fileSizeFormatted = metadata.fileSizeFormatted,
                        type = ActivityType.EXTRACT,
                        status = OperationStatus.SUCCESS,
                        itemsCount = extractedFiles
                    )
                )

                _uiState.value = UiState.Success(
                    message = "استخراج با موفقیت انجام شد!",
                    details = "فایل‌ها در پوشه $outputDirName ذخیره شدند."
                )
            } catch (e: ArchiveException.InvalidPassword) {
                _uiState.value = UiState.PasswordPrompt(metadata, isError = true)
            } catch (e: ArchiveException.MissingPart) {
                repository.addActivity(
                    ActivityItem(
                        fileName = metadata.fileName,
                        fileFormat = metadata.format,
                        fileSizeFormatted = metadata.fileSizeFormatted,
                        type = ActivityType.EXTRACT,
                        status = OperationStatus.FAILED
                    )
                )
                _uiState.value = UiState.Error(
                    title = "قسمت ناقص",
                    description = "قسمت ${e.partName} این آرشیو پیدا نشد."
                )
            } catch (e: ArchiveException.Cancelled) {
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                repository.addActivity(
                    ActivityItem(
                        fileName = metadata.fileName,
                        fileFormat = metadata.format,
                        fileSizeFormatted = metadata.fileSizeFormatted,
                        type = ActivityType.EXTRACT,
                        status = OperationStatus.FAILED
                    )
                )
                _uiState.value = UiState.Error(
                    title = "خطا در استخراج",
                    description = "فایل آرشیو ممکن است خراب باشد یا رمز عبور اشتباه باشد."
                )
            }
        }
    }

    fun startZipCreation(
        sourceUris: List<Uri>,
        outputName: String,
        password: String? = null
    ) {
        if (sourceUris.isEmpty()) return

        val app = getApplication<Application>()
        val cleanName = if (outputName.endsWith(".zip", ignoreCase = true)) outputName else "$outputName.zip"
        val outputFolder = File(app.getExternalFilesDir(null) ?: app.filesDir, "Created")
        if (!outputFolder.exists()) outputFolder.mkdirs()
        val targetZipFile = File(outputFolder, cleanName)

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = UiState.Processing(
                operationTitle = "در حال ساخت $cleanName",
                progress = ProgressState(cleanName, 0, sourceUris.size, 0)
            )

            try {
                val success = ArchiveEngine.createZip(app, sourceUris, targetZipFile, password) { progress ->
                    _uiState.value = UiState.Processing("در حال ساخت $cleanName", progress)
                }

                if (success) {
                    val fileSizeFormatted = StorageUtils.formatFileSize(targetZipFile.length())
                    repository.addActivity(
                        ActivityItem(
                            fileName = cleanName,
                            fileFormat = "ZIP",
                            fileSizeFormatted = fileSizeFormatted,
                            type = ActivityType.CREATE,
                            status = OperationStatus.SUCCESS,
                            itemsCount = sourceUris.size
                        )
                    )

                    _uiState.value = UiState.Success(
                        message = "فایل ZIP با موفقیت ساخته شد!",
                        details = "ذخیره شده با نام $cleanName ($fileSizeFormatted)"
                    )
                } else {
                    _uiState.value = UiState.Error(
                        title = "خطا در ساخت فایل",
                        description = "امکان ساخت فایل ZIP وجود نداشت."
                    )
                }
            } catch (e: ArchiveException.Cancelled) {
                _uiState.value = UiState.Idle
            } catch (e: Exception) {
                _uiState.value = UiState.Error(
                    title = "خطا در ساخت ZIP",
                    description = e.localizedMessage ?: "خطای ناشناخته رخ داد."
                )
            }
        }
    }

    fun cancelOperation() {
        activeJob?.cancel()
        activeJob = null
        _uiState.value = UiState.Idle
    }

    fun resetState() {
        _uiState.value = UiState.Idle
        currentInspectedUri = null
        currentInspectedMetadata = null
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }
}
