package dev.lmchat.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lmchat.data.update.AppRelease
import dev.lmchat.data.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class UpdateState(
    val release: AppRelease? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0L,
    val apk: File? = null,
    val message: String? = null,
    val toastMessage: String? = null,
    val visible: Boolean = false,
)

class UpdateViewModel(private val updater: AppUpdater) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    val installedVersion get() = updater.installedVersion
    private var job: Job? = null

    fun check(manual: Boolean = false) {
        if (job?.isActive == true || mutableState.value.visible) return
        job = viewModelScope.launch {
            mutableState.value = UpdateState(checking = true)
            try {
                val release = updater.check()
                mutableState.value = UpdateState(release = release, visible = release != null,
                    toastMessage = if (release == null && manual) "You're up to date." else null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = UpdateState(visible = manual,
                    message = if (manual) "Couldn't check for updates. Check your connection and try again." else null)
            }
        }
    }

    fun download() {
        val release = mutableState.value.release ?: return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(downloading = true, message = null, downloadedBytes = 0L)
            try {
                val apk = updater.download(release) { downloadedBytes ->
                    mutableState.value = mutableState.value.copy(downloadedBytes = downloadedBytes)
                }
                mutableState.value = mutableState.value.copy(downloading = false, apk = apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(downloading = false,
                    message = e.message ?: "Download failed. Please retry.")
            }
        }
    }

    fun toastShown() { mutableState.value = mutableState.value.copy(toastMessage = null) }

    fun error(message: String) { mutableState.value = mutableState.value.copy(message = message) }
    fun dismiss() {
        if (mutableState.value.downloading) return
        mutableState.value = mutableState.value.copy(visible = false)
    }

    companion object {
        fun factory(updater: AppUpdater): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(updater) as T
        }
    }
}
