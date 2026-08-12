package com.cua.a11

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.os.CountDownTimer
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val input = findViewById<EditText>(R.id.taskInput)
        val runBtn = findViewById<Button>(R.id.runBtn)
        val result = findViewById<TextView>(R.id.resultView)
        val logView   = findViewById<TextView>(R.id.logView)
        val logScroll = findViewById<ScrollView>(R.id.logScroll)
        val histBtn   = findViewById<Button>(R.id.histBtn)
        val clearBtn  = findViewById<Button>(R.id.clearBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        // ★ ImageButton 이다. <Button> 으로 잡으면 컴파일은 되고 실행할 때 ClassCastException.
        val micBtn    = findViewById<ImageButton>(R.id.micBtn)
        val cancelBtn = findViewById<Button>(R.id.cancelBtn)
        val turnsInput = findViewById<EditText>(R.id.maxTurnsInput)
        turnsInput.setText(prefs.getInt("max_turns", 20).toString())

        // 모델·사고수준 드롭다운 — 값 목록은 CuClient.MODELS / CuClient.THINKING 이 유일한 출처다.
        // 우선순위: 저장값(SharedPreferences) > 빌드값(local.properties) > CuClient 기본값.
        // 목록에 없는 값이 저장돼 있으면 modelIndex/thinkingIndex 가 기본값 자리로 떨궈 준다
        // (setSelection(-1) → selectedItemPosition == -1 → IndexOutOfBounds 방지).
        val modelSpinner = findViewById<Spinner>(R.id.modelSpinner)
        modelSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, CuClient.MODELS)
        modelSpinner.setSelection(CuClient.modelIndex(
            prefs.getString("model", null) ?: BuildConfig.GEMINI_MODEL))

        val thinkSpinner = findViewById<Spinner>(R.id.thinkingSpinner)
        thinkSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, CuClient.THINKING)
        thinkSpinner.setSelection(CuClient.thinkingIndex(
            prefs.getString("thinking", null) ?: BuildConfig.GEMINI_THINKING))

        histBtn.setOnClickListener {
            logView.text = loadHistory()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
        clearBtn.setOnClickListener {
            logFile().delete()
            logView.text = "(로그 지움)"
        }
        stopBtn.setOnClickListener {
            a11service.instance?.requestCancel()
            result.text = "중단 요청됨… 현재 단계가 끝나면 멈춥니다."
            stopBtn.isEnabled = false
        }

        // ── 카운트다운 ───────────────────────────────────────────────
        fun stopCountdown() {
            countdown?.cancel()
            countdown = null
            cancelBtn.visibility = View.GONE
        }

        // ── 실행 ─────────────────────────────────────────────────────
        // 텍스트('실행' 버튼)와 음성이 둘 다 여기로 들어온다. 로직이 두 벌이 되면
        // 나중에 한쪽만 고치는 사고가 나므로 반드시 한 곳에 둔다.
        // 클래스 멤버가 아닌 onCreate 안의 '지역 함수'인 이유: 위에서 findViewById 로
        // 잡아 둔 지역변수들을 그대로 캡처하려고. (람다면 return 마다 라벨이 필요하다.)
        fun startRun(task: String) {
            stopCountdown()          // ★ 카운트다운 중 '실행'을 눌러도 두 번 안 돌게
            if (task.isEmpty()) {
                result.text = "지시사항을 입력하세요. 예 : 설정 앱을 열어줘"
                return
            }
            val svc = a11service.instance
            if (svc == null) {
                //접근성 서비스 안켜져 있으면 안내 + 설정화면으로
                result.text = "접근성 서비스가 꺼져 있습니다. \n설정 > 접근성에서 'Android_run'을 켠 뒤 다시 실행하세요."
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            if (!Settings.canDrawOverlays(this)) {
                // '다른 앱 위에 표시' 권한 없으면 안내 + 설정화면으로
                result.text = "‘다른 앱 위에 표시’ 권한이 필요합니다.\n설정에서 켠 뒤 다시 실행하세요."
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
                return
            }
            // 빈칸·숫자 아님 → 20. 0 이하와 오타성 과대값만 잘라낸다(§0).
            val maxTurns = (turnsInput.text.toString().trim().toIntOrNull() ?: 20)
                .coerceIn(1, 40)
            turnsInput.setText(maxTurns.toString())   // ★ clamp 결과를 화면에 되돌린다
            val model = CuClient.MODELS[modelSpinner.selectedItemPosition]
            val thinking = CuClient.THINKING[thinkSpinner.selectedItemPosition]
            prefs.edit()
                .putInt("max_turns", maxTurns)
                .putString("model", model)
                .putString("thinking", thinking)
                .apply()
            runBtn.isEnabled = false
            micBtn.isEnabled = false      // ★ 실행 중 음성 다이얼로그가 뜨면 에이전트가 그걸 탭한다
            stopBtn.isEnabled = true
            logView.text = ""
            result.text = "실행 중… ($task)"
            thread {
                val runLog = StringBuilder()               // ← 이번 실행 로그 누적
                val r = try {
                    svc.runTask(task, maxTurns, model, thinking) { line ->
                        runLog.append(line).append("\n")   // 파일용(백그라운드)
                        runOnUiThread {                    // 화면용
                            logView.append(line + "\n")
                            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                        }
                    }
                } catch (e: Exception) {
                    "오류: ${e.message}"
                }
                runLog.append(r).append("\n")
                saveLog(task, runLog.toString())           // ← 끝나면 파일 저장(백그라운드)
                runOnUiThread {
                    result.text = r
                    runBtn.isEnabled = true
                    micBtn.isEnabled = true                // ★ 잠근 건 반드시 푼다
                    stopBtn.isEnabled = false
                }
            }
        }

        fun startCountdown(task: String) {
            stopCountdown()                  // 연달아 말했을 때 타이머가 겹치지 않게
            cancelBtn.visibility = View.VISIBLE
            // 500ms 틱 — 1000ms 로 두면 남은 초가 툭툭 끊겨 3→1 로 건너뛰어 보인다.
            countdown = object : CountDownTimer(3000, 500) {
                override fun onTick(left: Long) {
                    result.text = "${left / 1000 + 1}초 뒤 실행합니다 — “$task”"
                }
                override fun onFinish() {
                    stopCountdown()
                    startRun(task)
                }
            }.start()
        }

        // ── 인앱 음성 인식 ────────────────────────────────────────────
        // 다이얼로그 없이 말하는 중 실시간 자막이 입력칸에 찍힌다. 대가는 RECORD_AUDIO 권한.
        val voice = VoiceInput(
            this,
            onState = { listening ->
                micBtn.isSelected = listening      // @color/mic_tint 가 빨갛게 바꿔 준다
            },
            onPartial = { partial ->
                input.setText(partial)             // 확정 아님 — 계속 덮어쓴다
                input.setSelection(partial.length)
            },
            onFail = { msg -> result.text = msg },
        ) { heard ->
            input.setText(heard)        // ★ 자동 실행이어도 들은 내용을 반드시 보여 준다
            input.setSelection(heard.length)
            startCountdown(heard)
        }
        this.voice = voice

        val micPerm = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                result.text = "듣는 중… 말이 끝나면 마이크를 한 번 더 누르세요."
                voice.start()
            } else {
                result.text = "마이크 권한이 없어 음성 입력을 쓸 수 없습니다.\n" +
                        "설정 > 앱 > 권한에서 마이크를 허용하거나, 목표를 직접 입력하세요."
            }
        }

        // ── 버튼 배선 ────────────────────────────────────────────────
        micBtn.setOnClickListener {
            stopCountdown()                        // 카운트다운 중에 다시 말하려는 경우
            if (voice.isListening) {               // 두 번째 탭 = "말 다 했다"
                voice.stop()
                result.text = "인식 중…"
                return@setOnClickListener
            }
            if (!voice.isAvailable()) {
                // 대개 매니페스트 <queries> 의 RecognitionService 선언 누락이다.
                // 엔진이 멀쩡히 깔려 있어도 그 선언이 없으면 false 가 나온다.
                result.text = "이 기기에서 음성 인식을 쓸 수 없습니다. 목표를 직접 입력하세요."
                return@setOnClickListener
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                micPerm.launch(Manifest.permission.RECORD_AUDIO)   // 허용되면 콜백에서 start
                return@setOnClickListener
            }
            input.setText("")                      // 이전 문장이 남아 있으면 헷갈린다
            result.text = "듣는 중… 말이 끝나면 마이크를 한 번 더 누르세요."
            voice.start()
        }

        cancelBtn.setOnClickListener {
            stopCountdown()
            result.text = "취소했습니다. 내용을 고친 뒤 ‘실행’을 누르세요."
        }

        runBtn.setOnClickListener {
            voice.cancel()                         // 듣는 중에 '실행'을 누른 경우
            startRun(input.text.toString().trim())
        }
    }
    private fun logFile() = File(filesDir, "run_history.txt")
    private val prefs by lazy { getSharedPreferences("cua", MODE_PRIVATE) }

    /** 음성 인식 후 자동 실행까지의 카운트다운. 취소·화면 이탈 때 반드시 cancel() 한다. */
    private var countdown: CountDownTimer? = null

    /** 인앱 음성 인식기. onCreate 에서 만들고 onDestroy 에서 반드시 destroy 한다. */
    private var voice: VoiceInput? = null

    /**
     * ★ 화면을 벗어나면 카운트다운을 끊고 마이크를 닫는다.
     * 카운트다운을 안 끊으면 홈으로 나간 몇 초 뒤, 사용자가 보고 있지도 않은 상태에서
     * 접근성 서비스가 실기기를 조작하기 시작한다.
     * 마이크를 안 닫으면 앱이 뒤로 간 채로 계속 듣는다.
     * (onCreate 안의 지역 함수 stopCountdown 을 여기서 못 부르므로 직접 쓴다.)
     */
    override fun onStop() {
        super.onStop()
        countdown?.cancel()
        countdown = null
        findViewById<Button>(R.id.cancelBtn)?.visibility = View.GONE
        voice?.cancel()
    }

    /** SpeechRecognizer 는 destroy 하지 않으면 인식 서비스 바인딩이 남는다. */
    override fun onDestroy() {
        super.onDestroy()
        voice?.destroy()
        voice = null
    }

    /** 한 실행 로그를 타임스탬프+목표 헤더와 함께 파일 끝에 append. */
    private fun saveLog(task: String, body: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        logFile().appendText("===== $ts  |  $task =====\n$body\n\n")  // appendText=UTF-8
    }

    private fun loadHistory(): String {
        val f = logFile()
        return if (f.exists()) f.readText() else "(저장된 로그 없음)"
    }

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 결과 무시 */ }

}