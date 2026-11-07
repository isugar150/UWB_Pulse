package dev.uwbpulse.monitor

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Browser return. Never prints URI, codes, credentials or exceptions with URLs. */
class AuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "삼성 계정 연결 및 태그 조회 중…"; setPadding(32,64,32,32) })
        val callback = intent.data
        intent.data = null
        setIntent(Intent(this,AuthCallbackActivity::class.java))
        lifecycleScope.launch {
            val app = application as MonitorApplication
            if (callback != null && savedInstanceState == null) runCatching { app.account.completeLogin(callback) }
                .onSuccess { app.store.record("ACCOUNT_CONNECTED","owner device list loaded; secrets stored in Android Keystore envelope") }
                .onFailure { app.store.record("ACCOUNT_CONNECT_FAILED",it.javaClass.simpleName) }
            startActivity(Intent(this@AuthCallbackActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }
}
