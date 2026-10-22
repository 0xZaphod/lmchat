package dev.lmchat.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.lmchat.AppContainer
import dev.lmchat.data.local.ServerEntity
import dev.lmchat.data.scanner.NetworkScanner
import dev.lmchat.domain.DiscoveredServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ScannerViewModel(
    private val container: AppContainer,
) : ViewModel() {

    data class State(
        val port: Int = 0,
        val portText: String = "",
        val candidates: List<NetworkScanner.Candidate> = emptyList(),
        val selectedPrefixes: List<String> = emptyList(),
        val scanning: Boolean = false,
        val progressPercent: Int = 0,
        val scanned: Int = 0,
        val total: Int = 0,
        val results: List<DiscoveredServer> = emptyList(),
        val finished: Boolean = false,
        val notice: String? = null,
    )

    private val _portText = MutableStateFlow(container.settings.port.toString())
    val portText: StateFlow<String> = _portText.asStateFlow()

    private val _customSubnet = MutableStateFlow("")
    val customSubnet: StateFlow<String> = _customSubnet.asStateFlow()

    val selectedPrefixes = MutableStateFlow<List<String>>(emptyList())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _scanned = MutableStateFlow(0)
    val scanned: StateFlow<Int> = _scanned.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _results = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val results: StateFlow<List<DiscoveredServer>> = _results.asStateFlow()

    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var scanJob: Job? = null
    private val progressLock = Any()
    private var lastReportedPercent = -1

    /** Subnet prefixes derived from the device's own interfaces. */
    private val _candidates = MutableStateFlow(container.scanner.candidatePrefixes())
    val candidates = _candidates.asStateFlow()

    init {
        selectedPrefixes.value = candidates.value.map { it.prefix }
    }

    private fun port(): Int = portText.value.trim().toIntOrNull() ?: 0

    fun setPortText(value: String) {
        _portText.value = value
    }

    fun setCustomSubnet(value: String) {
        _customSubnet.value = value
    }

    fun togglePrefix(prefix: String) {
        selectedPrefixes.update {
            if (prefix in it) it - prefix else it + prefix
        }
    }

    fun start() {
        if (scanning.value) return
        val p = port()
        if (p !in 1..65535) {
            _notice.value = "Enter a valid port (1–65535)."
            return
        }
        val previous = candidates.value.map { it.prefix }
        _candidates.value = container.scanner.candidatePrefixes()
        val current = candidates.value.map { it.prefix }
        selectedPrefixes.value = if (selectedPrefixes.value.toSet() == previous.toSet()) current
            else selectedPrefixes.value.filter { it in current }.ifEmpty { current }
        val custom = customSubnet.value.trim()
        val prefixes = if (custom.isEmpty()) selectedPrefixes.value else listOf(custom)
        if (custom.isNotEmpty() && !isValidCidr(custom)) {
            _notice.value = "Enter a valid CIDR subnet, such as 192.168.68.0/22 (prefix /16–/32)."
            return
        }
        if (prefixes.isEmpty()) {
            _notice.value = "Select at least one subnet to scan."
            return
        }

        _results.value = emptyList()
        _scanned.value = 0
        _progress.value = 0
        _total.value = 0
        lastReportedPercent = -1
        _finished.value = false
        _scanning.value = true

        scanJob = viewModelScope.launch {
            try {
                container.scanner.scan(prefixes, p) { done, total ->
                    val percent = if (total > 0) done * 100 / total else 0
                    synchronized(progressLock) {
                        if (_total.value != total) _total.value = total
                        // A few dozen updates are enough for a smooth progress bar. Emitting
                        // once per address needlessly recomposes the whole settings card.
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            _scanned.value = done
                            _progress.value = percent
                        }
                    }
                }.collect { server ->
                    _results.update { list -> (list + server).sortedBy { it.host } }
                }
                _finished.value = true
                if (_results.value.isEmpty()) {
                    _notice.value = "No open ports found on $p."
                }
            } catch (_: Exception) {
                _finished.value = true
                _notice.value = "Scan failed."
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
        _finished.value = true
    }

    private fun isValidCidr(value: String): Boolean {
        val parts = value.split('/', limit = 2)
        if (parts.size != 2) return false
        val octets = parts[0].split('.')
        if (octets.size != 4 || octets.any { it.toIntOrNull() !in 0..255 }) return false
        return parts[1].toIntOrNull() in 16..32
    }

    /** Save a discovered server into the saved-servers list. */
    fun save(server: DiscoveredServer, onSaved: suspend (ServerEntity) -> Unit = {}) {
        viewModelScope.launch {
            val saved = container.serverRepository.saveDiscovered(server, null)
            onSaved(saved)
            _notice.value = "Saved ${server.host}:${server.port}."
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ScannerViewModel(container) as T
            }
    }
}
