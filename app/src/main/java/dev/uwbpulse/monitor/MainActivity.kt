package dev.uwbpulse.monitor

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.uwbpulse.monitor.domain.*
import dev.uwbpulse.monitor.service.ProbeService
import dev.uwbpulse.monitor.ui.ProbeViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val app get() = application as MonitorApplication
    private val store get() = app.store
    private val trackingNotificationsVisible = mutableStateOf(true)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProbeService.ensureNotificationChannel(this)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFFADA3FF),background=Color(0xFF0B0B12),surface=Color(0xFF171720))) {
                Surface(Modifier.fillMaxSize()) { MonitorScreen() }
            }
        }
    }
    override fun onStart() { super.onStart();store.activityVisible=true;store.record("ACTIVITY_VISIBLE","true") }
    override fun onResume() {
        super.onResume()
        val manager = getSystemService(NotificationManager::class.java)
        trackingNotificationsVisible.value = manager.areNotificationsEnabled() && manager.getNotificationChannel(ProbeService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        store.record("TRACKING_NOTIFICATION_STATUS","visible=${trackingNotificationsVisible.value}; channelImportance=${manager.getNotificationChannel(ProbeService.CHANNEL)?.importance}")
    }
    override fun onStop() { store.activityVisible=false;store.record("ACTIVITY_VISIBLE","false");super.onStop() }
    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Composable private fun MonitorScreen(vm: ProbeViewModel = viewModel()) {
        val state by vm.state.collectAsStateWithLifecycle()
        val prefs by vm.settings.state.collectAsStateWithLifecycle()
        val owned by vm.account.tags.collectAsStateWithLifecycle()
        val selected by vm.account.selectedId.collectAsStateWithLifecycle()
        val accountStatus by vm.account.status.collectAsStateWithLifecycle()
        val accountBusy by vm.account.busy.collectAsStateWithLifecycle()
        val backend by vm.backend.collectAsStateWithLifecycle()
        val events by vm.events.collectAsStateWithLifecycle()
        val error by vm.error.collectAsStateWithLifecycle()
        var settings by rememberSaveable { mutableStateOf(false) }
        var pending by remember { mutableStateOf<String?>(null) }
        var expanded by remember { mutableStateOf(false) }
        LaunchedEffect(prefs.monitoringEnabled,selected) {
            if (prefs.monitoringEnabled && selected != null && !state.running) {
                runCatching { startForegroundService(Intent(this@MainActivity,ProbeService::class.java).setAction(ProbeService.RESUME)) }
                    .onFailure { vm.error.value="자동 재개 실패: ${it.javaClass.simpleName}" }
            }
        }
        val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val ble = granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
            val uwbPermission = if (backend == Backend.PLATFORM && Build.VERSION.SDK_INT >= 36) Manifest.permission.RANGING else Manifest.permission.UWB_RANGING
            if (ble && (pending == "scan" || granted(uwbPermission))) {
                if (pending == "scan") vm.scan() else startMonitoring(selected,backend,vm)
            } else vm.error.value = "근처 기기와 UWB 권한을 허용해 주세요."
            pending = null
        }
        fun request(action: String) {
            pending=action
            val requested = mutableListOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
            if (action == "start") {
                requested += if (backend == Backend.PLATFORM && Build.VERSION.SDK_INT >= 36) Manifest.permission.RANGING else Manifest.permission.UWB_RANGING
                if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            }
            permissions.launch(requested.toTypedArray())
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("UWB PULSE",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                TextButton(onClick={settings=!settings}) { Text(if (settings) "모니터로 돌아가기" else "설정") }
            }
            if (!settings) {
                Text("내 태그와의 거리",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                if (owned.isEmpty()) {
                    Text(accountStatus)
                    Button(onClick={vm.login { startActivity(Intent(Intent.ACTION_VIEW,it.toUri())) }},enabled=!accountBusy,modifier=Modifier.fillMaxWidth()) { Text("삼성 계정으로 내 태그 연결") }
                } else Box {
                    OutlinedButton(onClick={expanded=true},enabled=!state.running,modifier=Modifier.fillMaxWidth()) {
                        Text((owned.firstOrNull { it.id == selected }?.name ?: "태그 선택") + " ▾")
                    }
                    DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                        owned.forEach { tag -> DropdownMenuItem(text={Text("${tag.name} · ${if (tag.supportsUwb) "UWB" else "UWB 미지원"}")},onClick={vm.selectOwnedTag(tag.id);expanded=false}) }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text(state.stage,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.titleMedium)
                        Text(state.measurement?.let { String.format(Locale.ROOT,"%.2f m",it.meters) } ?: "— m",fontSize=58.sp,fontWeight=FontWeight.SemiBold)
                        Text(state.detail,style=MaterialTheme.typography.bodyMedium)
                        state.measurement?.confidence?.let { Text("측정 신뢰도: ${when(it) { 2 -> "높음";1 -> "보통";else -> "낮음" }}",style=MaterialTheme.typography.bodySmall) }
                        if (state.running) Text("화면 OFF 수신 ${state.screenOffReports}회 · 앱 비표시 수신 ${state.backgroundReports}회",style=MaterialTheme.typography.bodySmall)
                        state.lastReportAgeMillis?.takeIf { it > 3_000 }?.let { Text("마지막 수신 ${it / 1000}초 전",color=MaterialTheme.colorScheme.error) }
                    }
                }
                Text("이탈 임계거리  ${"%.1f".format(prefs.policy.threshold)} m",style=MaterialTheme.typography.titleMedium)
                Slider(value=prefs.policy.threshold.toFloat(),onValueChange=vm::threshold,valueRange=1f..10f,steps=17)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("1 m");Text("10 m") }
                Text("${prefs.policy.dwellMillis / 1000.0}초 이상 초과하면 이탈 · ${"%.1f".format(prefs.policy.returnDistance)} m 이내에서 복귀",style=MaterialTheme.typography.bodySmall)
                Button(onClick={if (state.running) stopMonitoring() else request("start")},enabled=state.running || owned.any { it.id == selected && it.supportsUwb && it.hasOwnerKey },modifier=Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (state.running) "모니터링 중지" else "모니터링 시작")
                }
                Text(if (state.running && prefs.resumeAfterBoot) "추적 상태와 설정을 저장합니다. 재부팅 후 첫 잠금 해제 시 자동 재개됩니다."
                    else if (state.running) "저장된 거리 설정으로 계속 모니터링합니다." else "신호 중단을 거리 이탈로 처리하지 않습니다.",style=MaterialTheme.typography.bodySmall)
                events.lastOrNull()?.let { event ->
                    Text("마지막 이벤트",style=MaterialTheme.typography.titleMedium)
                    Text("${label(event.type)} · ${event.timestamp.substringAfter('T').take(8)}")
                    event.meters?.let { Text("실제 거리 ${"%.2f".format(it)} m · 임계값 ${"%.1f".format(event.threshold)} m") }
                }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            } else {
                Text("모니터링 설정",style=MaterialTheme.typography.headlineMedium)
                Toggle("재부팅 후 자동 모니터링 재개",prefs.resumeAfterBoot,vm::resumeAfterBoot)
                Text("마지막 모니터링 ON/OFF, 선택 태그와 거리 설정을 복원합니다. 계정 키 보호를 위해 첫 잠금 해제 후 재개합니다.",style=MaterialTheme.typography.bodySmall)
                Toggle("추적 중 알림 표시",trackingNotificationsVisible.value) { openTrackingNotificationSettings() }
                Text("이 스위치는 Android의 해당 알림 채널 설정을 엽니다. 알림을 꺼도 추적 서비스는 유지됩니다.",style=MaterialTheme.typography.bodySmall)
                Text(accountStatus)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={vm.login { startActivity(Intent(Intent.ACTION_VIEW,it.toUri())) }},enabled=!state.running && !accountBusy) { Text("삼성 계정 연결") }
                    TextButton(onClick={vm.refreshAccount()},enabled=!state.running && !accountBusy) { Text("내 태그 새로고침") }
                    TextButton(onClick={vm.logout()},enabled=!state.running && !accountBusy) { Text("연결 해제") }
                }
                Text("복귀 거리  ${"%.1f".format(prefs.policy.returnDistance)} m")
                Slider(value=prefs.policy.returnDistance.toFloat(),onValueChange=vm::returnDistance,valueRange=0f..(prefs.policy.threshold.toFloat()-0.5f),steps=((prefs.policy.threshold-0.5)*2).toInt().minus(1).coerceAtLeast(0))
                Text("이탈 지속 시간  ${prefs.policy.dwellMillis / 1000.0}초")
                Slider(value=(prefs.policy.dwellMillis / 1000f),onValueChange=vm::dwell,valueRange=1f..10f,steps=17)
                HorizontalDivider()
                Text("이벤트 알림",style=MaterialTheme.typography.titleMedium)
                MonitorEventType.entries.forEach { type -> Toggle(label(type),type in prefs.notifications) { vm.notification(type,it) } }
                Toggle("Tasker 이벤트 플러그인",prefs.taskerEnabled,vm::tasker)
                Text("Tasker → 프로필 → 이벤트 → 플러그인 → UWB Pulse 이벤트에서 유형을 선택하세요. 이 기기에 Tasker가 설치돼 있어야 합니다.",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                WebhookSettings(vm,prefs.webhookEnabled,prefs.includeTagId)
                HorizontalDivider()
                Text("배터리 및 진단",style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }},modifier=Modifier.fillMaxWidth()) { Text("배터리 최적화 설정") }
                Text("OS나 절전 정책으로 중단되면 앱에서 상태를 확인하세요. 화면 OFF 장기 시험 결과는 설치 안내에 기록합니다.",style=MaterialTheme.typography.bodySmall)
                if (Build.VERSION.SDK_INT >= 36) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=backend==Backend.PLATFORM,onClick={vm.chooseBackend(Backend.PLATFORM)},enabled=!state.running,label={Text("Android Ranging")})
                    FilterChip(selected=backend==Backend.JETPACK,onClick={vm.chooseBackend(Backend.JETPACK)},enabled=!state.running,label={Text("AndroidX UWB")})
                }
                Diagnostics(vm) { request("scan") }
                error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                Text("최근 이벤트",style=MaterialTheme.typography.titleMedium)
                events.takeLast(8).reversed().forEach { event -> Text("${event.timestamp.substringAfter('T').take(8)} · ${label(event.type)}",style=MaterialTheme.typography.bodySmall) }
            }
        }
    }
    @Composable private fun Toggle(title: String,value: Boolean,change: (Boolean)->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(title,Modifier.weight(1f).padding(top=12.dp));Switch(value,change) }
    }
    @Composable private fun WebhookSettings(vm: ProbeViewModel,enabled: Boolean,includeTag: Boolean) {
        val savedUrl by vm.webhookUrl.collectAsStateWithLifecycle()
        var url by remember(savedUrl) { mutableStateOf(savedUrl) }
        var token by remember { mutableStateOf("") }
        var clear by remember { mutableStateOf(false) }
        Text("HTTPS Webhook",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(value=url,onValueChange={url=it},label={Text("HTTPS URL")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=token,onValueChange={token=it},label={Text("인증 토큰 (비워두면 기존 값 유지)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Toggle("저장된 인증 토큰 삭제",clear) { clear=it }
        OutlinedButton(onClick={vm.saveWebhook(url,token,clear);token="";clear=false},modifier=Modifier.fillMaxWidth()) { Text("Webhook 설정 저장") }
        Toggle("Webhook 전송",enabled) { if (!it || savedUrl.isNotBlank()) vm.webhook(it) else vm.error.value="먼저 HTTPS URL을 저장하세요." }
        Toggle("태그 별칭 포함",includeTag,vm::includeTagId)
        Text("기본 전송: 이벤트·실제 거리·임계값·시각. 계정 ID는 전송하지 않습니다. 연결·읽기 각 5초, 최대 3회 재시도, 리디렉션 없음.",style=MaterialTheme.typography.bodySmall)
    }
    @Composable private fun Diagnostics(vm: ProbeViewModel,scan: ()->Unit) {
        val scanning by vm.scanning.collectAsStateWithLifecycle()
        val candidates by vm.candidates.collectAsStateWithLifecycle()
        val logs by vm.log.collectAsStateWithLifecycle()
        val state by vm.state.collectAsStateWithLifecycle()
        OutlinedButton(onClick={if (scanning) vm.stopScan() else scan()},enabled=!state.running,modifier=Modifier.fillMaxWidth()) { Text(if (scanning) "검색 중지" else "근처 태그 진단 검색 (15초)") }
        candidates.forEach { Text("${it.label} · ${if (it.cloudTagId != null) "소유자 서명 일치" else "매핑 미확인"}",style=MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick={
            val uri=FileProvider.getUriForFile(this,"$packageName.reports",store.export())
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"진단 보고서 내보내기"))
        },modifier=Modifier.fillMaxWidth()) { Text("진단 보고서 내보내기") }
        Text(logs.takeLast(8).joinToString("\n\n"),fontFamily=FontFamily.Monospace,fontSize=10.sp)
    }
    private fun startMonitoring(cloudId: String?,backend: Backend,vm: ProbeViewModel) {
        if (cloudId == null) { vm.error.value="삼성 계정에 연결해 내 태그를 선택하세요.";return }
        vm.stopScan()
        try { startForegroundService(Intent(this,ProbeService::class.java).putExtra("cloudId",cloudId).putExtra(ProbeService.BACKEND,backend.name)) }
        catch (e: Exception) { vm.error.value="모니터링 시작 실패: ${e.javaClass.simpleName}" }
    }
    private fun stopMonitoring() { startService(Intent(this,ProbeService::class.java).setAction(ProbeService.STOP)) }
    private fun openTrackingNotificationSettings() {
        ProbeService.ensureNotificationChannel(this)
        val manager = getSystemService(NotificationManager::class.java)
        val intent = if (manager.areNotificationsEnabled()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,ProbeService.CHANNEL)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)
        startActivity(intent)
    }
    private fun label(type: MonitorEventType) = when(type) {
        MonitorEventType.TAG_DISTANCE_EXCEEDED -> "임계거리 이탈"
        MonitorEventType.TAG_RETURNED -> "복귀"
        MonitorEventType.TAG_DISCONNECTED -> "태그 연결 중단"
        MonitorEventType.UWB_MONITORING_ERROR -> "모니터링 오류"
    }
}
