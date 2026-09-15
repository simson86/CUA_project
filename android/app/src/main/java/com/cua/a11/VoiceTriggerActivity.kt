package com.cua.a11

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 접근성 버튼이 띄우는 투명 카드. 존재 이유는 하나 — **마이크를 열기 위한 포그라운드 지위**다.
 * 접근성 서비스는 포그라운드가 아니라서 마이크를 못 연다(Android 9+). 인식이 끝나면 스스로
 * 사라지고, 실행은 서비스가 한다(`a11service.launchFromTrigger`).
 *
 * 흐름: 점검 → 듣기(실시간 자막) → 3초 카운트다운(취소 가능) → finish() → 서비스가 잠시 뒤 실행.
 * 설계 근거·버린 대안: docs/reference/android_run-handsfree-impl-2026-09-15.md
 *
 * ★ 음성 로직을 MainActivity 에서 복사하지 말 것 — 인식기는 VoiceInput 하나고, 여기는 콜백 배선만 다르다.
 */
class VoiceTriggerActivity : AppCompatActivity() {

    private var voice: VoiceInput? = null
    private var countdown: CountDownTimer? = null
    private var heard = ""            // 확정된 인식 결과. 비어 있으면 아직 못 들은 것
    private var launched = false      // 카운트다운 끝과 [지금 실행]이 겹쳐도 한 번만 실행되게

    private lateinit var status: TextView
    private lateinit var okBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_trigger)
        status = findViewById(R.id.triggerStatus)
        okBtn = findViewById(R.id.triggerOkBtn)

        // 카드 밖(어둡게 비친 뒤 앱 영역)을 누르면 취소. 카드 안 빈 곳의 탭은 여기서 소비해
        // 바깥 리스너로 올라가지 않게 한다 — 안 그러면 카드를 스치기만 해도 취소된다.
        findViewById<View>(R.id.triggerRoot).setOnClickListener { cancelAndFinish() }
        findViewById<View>(R.id.triggerCard).setOnClickListener { }
        findViewById<Button>(R.id.triggerCancelBtn).setOnClickListener { cancelAndFinish() }
        okBtn.setOnClickListener { if (heard.isNotEmpty()) launch() }   // 카운트다운 건너뛰기

        // ── 시작 전 점검. 걸리면 이유를 보여 주고 접는다 ──
        val svc = a11service.instance
            ?: return failAndFinish("접근성 서비스가 꺼져 있습니다.")
        // 실행 중에 새 지시를 받으면 두 에이전트가 같은 화면을 만진다. (runTask 가 한 번 더 막는다)
        if (svc.isRunning) return failAndFinish("이미 실행 중입니다. 끝난 뒤 다시 눌러 주세요.")
        // ★ 오버레이 권한 없이 돌리면 안 된다 — 위험 액션 확인 카드·인계 카드가 전부 오버레이다.
        //   권한이 없으면 그 카드들이 안 떠서, 사용자가 볼 수도 멈출 수도 없는 실행이 된다.
        //   MainActivity 도 같은 이유로 이 권한 없이는 실행을 안 시작한다.
        if (!Settings.canDrawOverlays(this))
            return failAndFinish("‘다른 앱 위에 표시’ 권한이 필요합니다. 앱을 열어 한 번 허용해 주세요.")

        val v = VoiceInput(
            this,
            onState = { },
            onPartial = { status.text = it },
            onFail = { msg -> failAndFinish(msg) },
        ) { said -> onHeard(said) }
        voice = v

        if (!v.isAvailable()) return failAndFinish("이 기기에서 음성 인식을 쓸 수 없습니다.")
        // ★ 권한을 여기서 처음 받게 하지 않는다 — 남의 앱 위에 권한 창이 튀어나오고, 거부되면
        //   카드가 설명 없이 사라진다. 앱의 마이크 버튼에서 한 번 받아 두게 안내만 한다.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return failAndFinish("마이크 권한이 필요합니다. 앱을 열어 마이크 버튼을 한 번 눌러 허용해 주세요.")

        status.text = "듣는 중… 말이 끝나면 잠시 기다리거나 접근성 버튼을 한 번 더 누르세요."
        v.start()
    }

    /**
     * 카드가 떠 있는 중에 접근성 버튼을 또 누른 경우. singleTask 라 새 카드가 안 뜨고 여기로 온다.
     * 앱의 마이크 버튼과 같은 약속 — 듣는 중의 두 번째 누름은 "말 다 했다".
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (voice?.isListening == true) {
            voice?.stop()
            status.text = "인식 중…"
        }
    }

    private fun onHeard(said: String) {
        heard = said
        okBtn.isEnabled = true
        countdown?.cancel()
        // 500ms 틱 — 1000ms 면 남은 초가 3→1 로 건너뛰어 보인다(MainActivity 와 같은 이유).
        countdown = object : CountDownTimer(3000, 500) {
            override fun onTick(left: Long) {
                status.text = "${left / 1000 + 1}초 뒤 실행 — “$said”"
            }
            override fun onFinish() { launch() }
        }.start()
    }

    /** 카드를 먼저 지우고, 실행은 서비스에 넘긴다. 서비스가 카드가 사라질 시간을 기다린 뒤 시작한다. */
    private fun launch() {
        if (launched) return
        launched = true
        countdown?.cancel(); countdown = null
        voice?.cancel()
        val svc = a11service.instance ?: return failAndFinish("접근성 서비스가 꺼졌습니다.")
        finish()
        svc.launchFromTrigger(heard)
    }

    private fun cancelAndFinish() {
        countdown?.cancel(); countdown = null
        voice?.cancel()
        if (!isFinishing) finish()
    }

    /** 이유를 잠깐 보여 주고 접는다. 바로 닫으면 사용자는 버튼이 안 눌린 줄 안다. */
    private fun failAndFinish(msg: String) {
        countdown?.cancel(); countdown = null
        status.text = msg
        okBtn.isEnabled = false
        status.postDelayed({ if (!isFinishing) finish() }, FAIL_SHOW_MS)
    }

    /**
     * ★ 화면을 벗어나면 무조건 접는다. 안 보이는 카드의 카운트다운이 끝나 실기기를 조작하면 안 된다
     *   (MainActivity.onStop 과 같은 이유). 이미 launch() 했으면 실행은 서비스 쪽이라 영향 없다.
     */
    override fun onStop() {
        super.onStop()
        cancelAndFinish()
    }

    /** SpeechRecognizer 는 destroy 하지 않으면 인식 서비스 바인딩이 남는다. */
    override fun onDestroy() {
        super.onDestroy()
        voice?.destroy()
        voice = null
    }

    private companion object {
        const val FAIL_SHOW_MS = 2500L
    }
}
