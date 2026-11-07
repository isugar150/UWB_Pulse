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
import dev.uwbpulse.monitor.automation.MonitorEvent
import dev.uwbpulse.monitor.data.BondedDevice
import dev.uwbpulse.monitor.domain.*
import dev.uwbpulse.monitor.service.ProbeService
import dev.uwbpulse.monitor.ui.ProbeViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
                        if (!state.running) lastStateText(prefs.last,selected,prefs.awayTagAliases)?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                        state.lastReportAgeMillis?.takeIf { it > 3_000 }?.let { Text("마지막 수신 ${it / 1000}초 전",color=MaterialTheme.colorScheme.error) }
                    }
                }
                Text("이탈 임계거리  ${"%.1f".format(prefs.policy.threshold)} m",style=MaterialTheme.typography.titleMedium)
                Slider(value=prefs.policy.threshold.toFloat(),onValueChange=vm::threshold,valueRange=1f..10f,steps=17)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("1 m");Text("10 m") }
                ReturnDistanceSlider(prefs.policy,vm::returnDistance)
                Text("${prefs.policy.dwellMillis / 1000.0}초 이상 초과하면 이탈 · ${"%.1f".format(prefs.policy.returnDistance)} m 이내에서 복귀",style=MaterialTheme.typography.bodySmall)
                Button(onClick={if (state.running) stopMonitoring() else request("start")},enabled=state.running || owned.any { it.id == selected && it.supportsUwb && it.hasOwnerKey },modifier=Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (state.running) "모니터링 중지" else "모니터링 시작")
                }
                Text(if (state.running && prefs.resumeAfterBoot) "추적 상태와 설정을 저장합니다. 재부팅 후 첫 잠금 해제 시 자동 재개됩니다."
                    else if (state.running) "저장된 거리 설정으로 계속 모니터링합니다." else "거리 이벤트는 실제 UWB 거리로만 만듭니다. 스위치는 설정에 따라 끊김도 이탈로 봅니다.",style=MaterialTheme.typography.bodySmall)
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
                ReturnDistanceSlider(prefs.policy,vm::returnDistance)
                Text("이탈 지속 시간  ${prefs.policy.dwellMillis / 1000.0}초")
                Slider(value=(prefs.policy.dwellMillis / 1000f),onValueChange=vm::dwell,valueRange=1f..10f,steps=17)
                HorizontalDivider()
                Text("추적 주기",style=MaterialTheme.typography.titleMedium)
                Text("UWB 갱신 주기",style=MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TrackingRate.entries.forEach { rate ->
                        FilterChip(selected=prefs.trackingRate==rate,onClick={vm.trackingRate(rate)},enabled=!state.running,label={Text(rateLabel(rate))})
                    }
                }
                Text("기본은 절전 600ms입니다. 태그의 UWB를 켜 둔 시간이 태그 배터리 소모의 주원인으로 보여 갱신을 줄였고, 차량 잠금에는 충분합니다. 표준 240ms(이전 기본)와 빠름 120ms는 반응이 빠른 대신 태그 배터리를 더 씁니다. 태그 설정에 포함되므로 모니터링을 중지한 상태에서 바꾸고 다음 시작부터 적용됩니다. 120ms·600ms는 한 기기에서 콜백 간격만 확인했고 배터리 절감 효과는 아직 측정하지 않았습니다.",style=MaterialTheme.typography.bodySmall)
                val rescanChoices = RescanPolicy.choicesMillis
                val rescanIndex = rescanChoices.indexOfFirst { it >= prefs.rescanMaxMillis }.let { if (it < 0) rescanChoices.lastIndex else it }
                Text("이탈 후 태그 재탐색 간격  ${durationLabel(rescanChoices[rescanIndex])}",style=MaterialTheme.typography.bodyMedium)
                Slider(value=rescanIndex.toFloat(),onValueChange={ val index = it.toInt().coerceIn(0,rescanChoices.lastIndex); if (index != rescanIndex) vm.rescanMaxMillis(rescanChoices[index]) },
                    valueRange=0f..rescanChoices.lastIndex.toFloat(),steps=rescanChoices.size - 2)
                Text("연결이 끊기면 2·5·15·60초로 간격을 늘려 가며 태그를 다시 찾습니다. 이 값은 그 최대 간격이며 기본은 1분입니다. 길수록 배터리를 아끼지만 복귀 후 다시 잡히기까지 오래 걸릴 수 있습니다. 바로 적용됩니다.",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                CarModeSettings(vm,prefs.carMode,prefs.carAddress,prefs.carName,state.running)
                HorizontalDivider()
                Text("이벤트 알림",style=MaterialTheme.typography.titleMedium)
                MonitorEventType.entries.forEach { type -> Toggle(label(type),type in prefs.notifications) { vm.notification(type,it) } }
                Toggle("Tasker 이벤트 플러그인",prefs.taskerEnabled,vm::tasker)
                Text("Tasker → 프로필 → 이벤트 → 플러그인 → UWB Pulse 이벤트에서 유형을 선택하세요. 이 기기에 Tasker가 설치돼 있어야 합니다.",style=MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                WebhookSettings(vm,prefs.webhookEnabled,prefs.includeTagId)
                HorizontalDivider()
                SmartThingsSettings(vm,prefs.smartThingsEnabled,prefs.offOnLinkLoss,prefs.pulseOnDeparture)
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
    /** The return distance may be set anywhere from MIN_RETURN up to half a metre below the exit threshold. */
    @Composable private fun ReturnDistanceSlider(policy: DistancePolicy,change: (Float)->Unit) {
        val min = DistancePolicy.MIN_RETURN.toFloat()
        val max = policy.threshold.toFloat() - 0.5f
        Text("복귀 거리  ${"%.1f".format(policy.returnDistance)} m",style=MaterialTheme.typography.titleMedium)
        if (max > min) {
            Slider(value=policy.returnDistance.toFloat().coerceIn(min,max),onValueChange=change,valueRange=min..max,steps=((max-min)*2).toInt().minus(1).coerceAtLeast(0))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("%.1f m".format(min));Text("%.1f m".format(max)) }
        } else Text("임계거리가 1 m일 때 복귀 거리는 0.5 m로 고정됩니다.",style=MaterialTheme.typography.bodySmall)
    }
    @Composable private fun Toggle(title: String,value: Boolean,change: (Boolean)->Unit) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(title,Modifier.weight(1f).padding(top=12.dp));Switch(value,change) }
    }
    @Composable private fun CarModeSettings(vm: ProbeViewModel,enabled: Boolean,address: String,name: String,running: Boolean) {
        val bonded by vm.bondedDevices.collectAsStateWithLifecycle()
        LaunchedEffect(Unit) { if (granted(Manifest.permission.BLUETOOTH_CONNECT)) vm.refreshBonded() }
        Text("차량 블루투스 연동",style=MaterialTheme.typography.titleMedium)
        Toggle("차량 블루투스가 끊기면 추적 시작",enabled) { if (running) vm.error.value = "모니터링을 중지한 상태에서 바꾸세요." else vm.carMode(it) }
        Text(if (address.isBlank()) "차량 기기: 선택 안 됨" else "차량 기기: ${name.ifBlank { "선택됨" }}",style=MaterialTheme.typography.bodyMedium)
        if (bonded.isEmpty()) Text("등록된 블루투스 기기가 없거나 근처 기기 권한이 없습니다.",style=MaterialTheme.typography.bodySmall)
        bonded.forEach { device: BondedDevice ->
            FilterChip(selected=device.address == address,onClick={ if (running) vm.error.value = "모니터링을 중지한 상태에서 바꾸세요." else vm.chooseCar(device) },label={Text(device.name)})
        }
        Text("켜면 차량이 폰에 블루투스로 연결돼 있는 동안 태그를 검색·연결·측정하지 않아 태그 배터리를 거의 쓰지 않습니다. 차량이 연결됐다가 끊기면(3초 확인) 그때 태그에 연결해 거리를 재고, 이탈 임계거리를 넘거나 태그가 30초 넘게 닿지 않으면 스위치를 끄고 다시 쉽니다. 차량이 다시 연결되면 스위치를 켭니다. 시작할 때 차량이 연결돼 있지 않으면 아무것도 하지 않고 차량이 연결되길 기다립니다. 거리 이벤트는 실제 UWB 거리로만 만들어지며 Tasker·Webhook 복귀 이벤트는 이 모드에서 차량 연결로 대신하지 않습니다. 모니터링을 중지한 상태에서 바꾸고 다음 시작부터 적용됩니다. 꺼 두면 지금처럼 계속 측정합니다.",style=MaterialTheme.typography.bodySmall)
    }
    @Composable private fun SmartThingsSettings(vm: ProbeViewModel,enabled: Boolean,offOnLinkLoss: Boolean,pulse: Boolean) {
        val view by vm.smartThings.collectAsStateWithLifecycle()
        var clientId by remember(view.clientId) { mutableStateOf(view.clientId) }
        var secret by remember { mutableStateOf("") }
        var deviceId by remember(view.deviceId) { mutableStateOf(view.deviceId) }
        var redirect by remember(view.redirectUri) { mutableStateOf(view.redirectUri) }
        var code by remember { mutableStateOf("") }
        Text("SmartThings 가상 스위치",style=MaterialTheme.typography.titleMedium)
        Text(when { view.connected -> "OAuth 앱 연결됨 (선택 사항)"; view.samsungLogin -> "삼성 계정 로그인으로 사용 가능 · 별도 연결 불필요"; else -> "삼성 계정 로그인이 필요합니다" },style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(value=clientId,onValueChange={clientId=it},label={Text("OAuth 클라이언트 ID")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=secret,onValueChange={secret=it},label={Text("클라이언트 비밀 (비워두면 기존 값 유지)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=deviceId,onValueChange={deviceId=it},label={Text("가상 스위치 기기 ID")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=redirect,onValueChange={redirect=it},label={Text("리디렉션 주소 (OAuth 앱과 같게)")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=code,onValueChange={code=it},label={Text("승인 코드 (연결할 때 한 번만 붙여넣기)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={vm.saveSmartThings(clientId,secret,deviceId,redirect,code);secret="";code=""}) { Text("저장 및 연결") }
            OutlinedButton(onClick={ if (clientId.isNotBlank()) runCatching { startActivity(Intent(Intent.ACTION_VIEW,dev.uwbpulse.monitor.domain.SmartThingsSwitch.authorizationUrl(clientId.trim(),redirect.trim()).toUri())) } else vm.error.value="먼저 클라이언트 ID를 입력하세요." }) { Text("승인 페이지 열기") }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={vm.testSmartThings(true)}) { Text("스위치 켜기 시험") }
            OutlinedButton(onClick={vm.testSmartThings(false)}) { Text("스위치 끄기 시험") }
            OutlinedButton(onClick={vm.testPulse()}) { Text("켰다 끄기 시험") }
            TextButton(onClick={vm.disconnectSmartThings()}) { Text("연결 해제") }
        }
        OutlinedButton(onClick={vm.probeSmartThingsLogin()},modifier=Modifier.fillMaxWidth()) { Text("삼성 로그인 토큰으로 연결 시험 (읽기·쓰기)") }
        Toggle("가까우면 켜기 · 멀어지면 끄기",enabled) { vm.smartThingsEnabled(it) }
        Toggle("이탈 때 켰다 끄기",pulse) { vm.pulseOnDeparture(it) }
        Text("이탈할 때마다 스위치를 켠 뒤 1.5초 뒤 끕니다. 이미 꺼져 있어도 꺼짐으로 바뀌는 순간이 생겨 ‘꺼지면 잠금’ 자동화가 다시 실행됩니다. ‘켜지면’ 조건의 자동화가 있으면 이탈 때마다 한 번 실행되니 주의하세요. 끄면 끄기 신호만 보냅니다.",style=MaterialTheme.typography.bodySmall)
        Toggle("신호가 끊기면 멀어진 것으로 보고 끄기",offOnLinkLoss) { vm.offOnLinkLoss(it) }
        Text("차 문이 닫히면 전파가 막혀 마지막 거리가 가까운 채로 끊깁니다. 가까웠던 태그와 30초 넘게 연결이 끊기면 스위치를 한 번 끄고(차량 잠금), 다시 연결돼 복귀 거리 안으로 들어오면 켭니다. Bluetooth를 직접 끈 경우는 제외합니다. Tasker·Webhook·알림의 거리 이벤트는 이 규칙과 무관하게 실제 UWB 거리로만 만들어집니다.",style=MaterialTheme.typography.bodySmall)
        Text("기기 ID만 저장하면 앱에 저장된 삼성 계정 로그인으로 SmartThings 서버에 직접 신호를 보냅니다. OAuth 항목은 비워 두어도 됩니다. 이벤트는 발생 후 30초가 지나면 폐기합니다. 시험 버튼은 실제 이탈 없이 스위치만 바꾸며, 연결된 SmartThings 자동화가 있으면 그것이 실행됩니다. 비밀과 토큰은 Keystore로 암호화해 저장하고 로그에 남기지 않습니다.",style=MaterialTheme.typography.bodySmall)
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
    private fun rateLabel(rate: TrackingRate) = when(rate) {
        TrackingRate.FAST -> "빠름 ${rate.intervalMillis}ms"
        TrackingRate.NORMAL -> "표준 ${rate.intervalMillis}ms"
        TrackingRate.SAVER -> "절전 ${rate.intervalMillis}ms (기본)"
    }
    private fun durationLabel(millis: Long) = if (millis >= 60_000) "${millis / 60_000}분" else "${millis / 1000}초"
    private fun lastStateText(last: LastObservation,tag: String?,awayAliases: Set<String>): String? {
        val meters = last.meters ?: return null
        val at = last.atEpochMillis ?: return null
        val away = tag?.let { MonitorEvent.tagAlias(packageName,it) in awayAliases } == true
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M/d HH:mm:ss"))
        return String.format(Locale.ROOT,"마지막 상태 · %s · %.2f m · %s",if (away) "이탈" else "범위 안",meters,time)
    }
    private fun label(type: MonitorEventType) = when(type) {
        MonitorEventType.TAG_DISTANCE_EXCEEDED -> "임계거리 이탈"
        MonitorEventType.TAG_RETURNED -> "복귀"
        MonitorEventType.TAG_DISCONNECTED -> "태그 연결 중단"
        MonitorEventType.UWB_MONITORING_ERROR -> "모니터링 오류"
    }
}
