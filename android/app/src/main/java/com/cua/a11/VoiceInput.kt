package com.cua.a11

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 앱 안에서 직접 음성을 듣는 인식기 (`SpeechRecognizer` 래퍼).
 *
 * 구글 음성 다이얼로그(`RecognizerIntent`)와 **같은 인식 엔진**을 쓴다 — 인식률은 동일하고,
 * 다른 점은 화면을 우리가 그린다는 것뿐이다. 그 대가로 이쪽만 필요한 것들:
 *  - `RECORD_AUDIO` 런타임 권한 (마이크를 우리가 연다)
 *  - `AndroidManifest` 의 `<queries>` 에 `android.speech.RecognitionService` 선언
 *    (Android 11+ 패키지 가시성. 없으면 [isAvailable] 이 늘 false 다)
 *  - 생명주기 관리 — [destroy] 를 안 부르면 인식 서비스 바인딩이 샌다
 *
 * 얻는 것은 **말하는 중 실시간 자막**([onPartial])이다.
 *
 * ★ 모든 메서드는 **메인 스레드**에서 불러야 한다. `SpeechRecognizer` 의 요구사항이다.
 *
 * @param ctx       액티비티 컨텍스트
 * @param onState   듣기 시작/끝 (true=듣는 중). 마이크 아이콘 상태를 바꾸는 용도
 * @param onPartial 말하는 중간 결과. 확정 아님 — 계속 바뀐다
 * @param onFail    사용자에게 보여 줄 실패 메시지(한국어로 변환된 것)
 * @param onDone    최종 인식 문자열. 비어 있으면 [onFail] 로 간다
 */
class VoiceInput(
    private val ctx: Context,
    private val onState: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFail: (String) -> Unit,
    private val onDone: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    /** 듣는 중인지. 마이크 버튼이 '시작'인지 '끝내기'인지 판단하는 데 쓴다. */
    var isListening = false
        private set

    /**
     * 이 기기에서 인앱 인식을 쓸 수 있는지.
     * ★ 매니페스트 `<queries>` 선언이 없으면 **엔진이 멀쩡히 있어도 false** 다.
     */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    /**
     * 듣기 시작. `RECORD_AUDIO` 권한이 이미 있어야 한다(없으면 `ERROR_INSUFFICIENT_PERMISSIONS`).
     * `languageTag` 를 null 로 주면 폰 기본 언어를 따른다.
     */
    fun start(languageTag: String? = "ko-KR") {
        if (isListening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(ctx).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        // ★ 재사용 인스턴스는 앞선 세션이 덜 정리돼 ERROR_RECOGNIZER_BUSY 를 내는 기기가 있다.
        //   시작 전에 한 번 비워 준다(듣고 있지 않을 때의 cancel 은 무해하다).
        r.cancel()

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (languageTag != null) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            // 이게 이 경로의 존재 이유 — 켜야 onPartialResults 가 온다
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 일부 기기의 구글 앱이 이게 없으면 ERROR_CLIENT 로 튕긴다
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        }
        isListening = true
        onState(true)
        r.startListening(i)
    }

    /**
     * "말 다 했다" — 녹음을 끊고 **최종 결과를 요청**한다.
     * 결과는 곧이어 [onDone] 이나 [onFail] 로 온다(여기서 바로 끝나지 않는다).
     */
    fun stop() {
        if (isListening) recognizer?.stopListening()
    }

    /** 결과 없이 즉시 버린다. 화면을 벗어날 때 쓴다. */
    fun cancel() {
        if (!isListening) return
        isListening = false
        recognizer?.cancel()
        onState(false)
    }

    /** `onDestroy` 에서 반드시 부른다. 안 부르면 인식 서비스 바인딩이 남는다. */
    fun destroy() {
        isListening = false
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}          // 음량 파형을 그릴 거면 여기
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}                     // 아직 결과 전 — 여기서 끝내면 안 된다
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val t = partialResults?.first().orEmpty()
            if (t.isNotBlank()) onPartial(t)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            onState(false)
            val t = results?.first().orEmpty()
            if (t.isEmpty()) onFail("못 알아들었습니다. 다시 말해 주세요.") else onDone(t)
        }

        override fun onError(error: Int) {
            // ★ 결과를 이미 준 뒤에 에러가 한 번 더 오는 기기가 있다.
            //   그때 onFail 을 부르면 멀쩡히 인식된 문장이 오류 메시지로 덮인다.
            if (!isListening) return
            isListening = false
            onState(false)
            onFail(message(error))
        }
    }

    /** 결과 번들에서 1순위 후보만 꺼낸다. 부분 결과·최종 결과가 같은 키를 쓴다. */
    private fun Bundle.first(): String? =
        getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    /** 에러 코드를 사용자가 읽을 수 있는 말로. 숫자를 남겨 로그 대조가 되게 한다. */
    private fun message(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "마이크를 열 수 없습니다."
        SpeechRecognizer.ERROR_CLIENT -> "인식이 중단되었습니다."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 필요합니다."
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류 — 인터넷 연결을 확인하세요."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 응답이 늦습니다. 다시 시도하세요."
        SpeechRecognizer.ERROR_NO_MATCH -> "못 알아들었습니다. 다시 말해 주세요."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기가 사용 중입니다. 잠시 뒤 다시 누르세요."
        SpeechRecognizer.ERROR_SERVER -> "인식 서버 오류입니다."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "말소리가 들리지 않았습니다."
        else -> "음성 인식 오류 (코드 $code)"   // API 31+ 에 추가된 코드들도 여기로
    }
}
