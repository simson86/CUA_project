package com.cua.a11.memory

/**
 * 리플렉터가 뽑은 후보 1건. **문장이 아니라 칸이다**(설계 §2).
 *
 * `trigger` → `consequence` 두 칸뿐인 것이 이 설계에서 가장 강한 방어다(§3 집행 A층).
 * 잔액·이름·인증번호를 넣을 **자리 자체가 없다.** 문장을 검사해서 걸러내는 것보다
 * 새어나갈 칸을 없애는 쪽이 훨씬 강하다 — 검사는 놓칠 수 있지만 없는 칸은 못 채운다.
 *
 * ⚠️ **여기에 자유 문장 칸을 더하지 말 것.** `text` 를 그대로 받게 만드는 순간 A층이
 * 사라지고, 남는 방어는 정규식(B층)과 모델(C층)뿐이다. 둘 다 놓친다.
 */
data class Candidate(
    val kind: String,            // APP_FACT | PITFALL
    val trigger: String,         // 무엇을 하면
    val consequence: String,     // 무엇이 일어나나
    val pkg: String?,            // APP_FACT — 쉼표로 여러 개 가능
    val keywords: String?,       // PITFALL — 동의어 포함
    val sourceTurn: Int,         // 규칙 A 가 이걸로 자른다
) {
    /**
     * 주입 문장으로 조립한다. **저장 시점에 한 번** 조립해 `memory.text` 에 넣는다 —
     * 스키마에 `trigger`/`consequence` 칸이 없다(설계 부록의 DDL 도 `text` 하나뿐이다).
     *
     * 조립을 여기 둔 이유: 템플릿을 아는 곳이 하나여야 A층의 보장이 유지된다. 호출부가
     * 제 나름대로 문자열을 이어 붙이기 시작하면 그때부터 아무 값이나 들어갈 수 있다.
     */
    fun assemble(): String {
        val t = trigger.trim().trimEnd('.')
        val c = consequence.trim().trimEnd('.')
        return "$t → $c."
    }

    fun toMemory(runId: String?, pkgVersion: Long?): MemoryEntity = MemoryEntity(
        kind = kind,
        text = assemble(),
        pkg = pkg,
        keywords = keywords,
        // 리플렉터가 만든 것은 **검역부터**다. score 도 기본값 1 — 사람이 쓴 것의
        // HUMAN_SCORE(2)를 쓰면 안 된다. 아직 아무도 검증하지 않았다.
        state = "PENDING",
        source = "reflector",
        sourceRunId = runId,
        pkgVersion = pkgVersion,
        timeAdded = System.currentTimeMillis(),
    )
}

/**
 * 설계 §3 — **저장 전에 무엇을 막을까.** 형식이 완벽하고 사실이기까지 한데 저장하면
 * 안 되는 것을 거른다.
 *
 * ②(쓸모 있나)와 다른 질문이다. ③은 **허용되나**를 묻는다. 승격 심사는 "반복되는
 * 사실인가"만 보므로 아래 같은 후보는 심사를 **통과한다**:
 *
 * ```
 * kind: APP_FACT · pkg: com.kakao.talk
 * text: "대화 목록 맨 위는 보통 '엄마'다"     ← 형식도 맞고 재현도 된다. 그래도 안 된다.
 * ```
 *
 * ## 완벽할 필요가 없다 — 비용이 비대칭이다
 * 오탐(구조인데 버림)은 **기억 하나를 잃을 뿐**이고, 미탐(내용인데 통과)은 **개인정보를
 * 영구 저장**한다. 게다가 기억은 **재관측이 가능**해서 오늘 버려도 같은 앱을 다음에 쓰면
 * 다시 뽑힌다. 버리는 비용이 거의 0이므로 **애매하면 무조건 버린다.**
 *
 * ## 이 파일이 맡는 것은 네 겹 중 둘
 * | 층 | 누가 | 성질 |
 * |---|---|---|
 * | A | 구조적 템플릿([Candidate]) | 값 넣을 칸이 없음 — 어길 수 없음 |
 * | **B** | **이 파일** | 결정론적 |
 * | C | 모델 (reconciliation, 5c) | 비결정적 |
 * | D | 사람 (목록 UI) | 최종 |
 *
 * ⚠️ **명세 B층의 '원문 대조'는 구현하지 않았다.** *"후보의 토큰이 그 실행의 화면
 * 텍스트에 그대로 있으면 의심한다"* 인데, **우리는 화면 텍스트를 어디에도 저장하지
 * 않는다**(`episode` 에 있는 건 모델이 쓴 `intent` 요약뿐이다).
 *
 * `intent` 로 대신하면 안 된다 — 후보가 `intent` 에서 파생되므로 토큰이 겹치는 게
 * **정상**이고, 그러면 전부 걸린다. 화면 텍스트를 저장하는 건 그 자체가 §3 이 막으려는
 * 것을 DB 에 쌓는 일이라 더 나쁘다. 그래서 이 구멍은 **열어 둔 채 기록한다** —
 * 대신 리플렉터의 입력이 애초에 화면이 아니라 요약이라 유출 경로가 그만큼 좁다.
 */
object CandidateFilter {

    /** 통과면 null, 아니면 **사람이 읽을 사유**(실행 로그·목록에 그대로 뜬다). */
    fun reject(c: Candidate, taintedTurns: Set<Int>): String? {
        // ── 규칙 A — 안전은 학습 대상이 아니다 ──────────────────────────
        //  "저번엔 확인 없이 됐다"가 사실로 승격되면 안전 게이트가 무력화된다.
        //  ★ 내용을 판정하지 않는다. "이게 안전 관련인가"를 문장으로 보려 하면 모델
        //    판단이 끼어들어 비결정적이 된다. 어느 턴에 확인 카드가 떴는지는 우리가
        //    이미 안다(episode.hadSafety) — 그 턴에서 나온 후보를 통째로 버리면
        //    판정이 필요 없다.
        if (c.sourceTurn in taintedTurns)
            return "안전 확인이 붙었던 턴(${c.sourceTurn})에서 나온 후보"

        // ── 규칙 B — 화면의 '구조'는 저장하고 '내용'은 저장하지 않는다 ──
        //  판별 기준 한 줄: **다른 사람 폰에서도 참인가?**
        //  참이면 구조, 그 사람에게만 참이면 내용.
        //
        //  pkg 는 검사하지 않는다 — 패키지명에는 원래 숫자가 들어간다
        //  (com.sec.android.gallery3d). 검사 대상은 **모델에 실릴 문장**뿐이다.
        val body = listOfNotNull(c.trigger, c.consequence, c.keywords).joinToString(" ")

        for ((why, re) in PATTERNS) if (re.containsMatchIn(body)) return why
        return null
    }

    /**
     * 걸리면 버리는 패턴들. **순서는 사유 문구의 우선순위**일 뿐 판정에는 영향이 없다.
     *
     * 왜 '숫자 4자리'인가 — 인증번호(6)·카드(4묶음)·잔액·연도·계좌가 전부 여기 걸린다.
     * 반대로 구조를 말하는 숫자는 대개 작다: "3단계", "탭이 5개", "6자리다"(자릿수를
     * *말하는* 것은 통과한다 — 값이 아니라 형식이니 맞는 동작이다).
     */
    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "이메일 주소가 들어 있음" to
            Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""),
        // 통화 기호 + 숫자, 또는 숫자 + 원/won. 잔액·가격이 여기 걸린다.
        "금액이 들어 있음" to
            Regex("""[₩$€¥£]\s?\d|\d[\d,.]*\s?(원|won|KRW|USD)""", RegexOption.IGNORE_CASE),
        // 구분자가 낀 전화번호(010-1234-5678). 아래 4자리 규칙에도 걸리지만
        // 사유를 구체적으로 주려고 먼저 본다.
        "전화번호로 보이는 값이 들어 있음" to
            Regex("""\d{2,3}[-.\s]\d{3,4}[-.\s]\d{4}"""),
        // ★ 마지막 그물. 쉼표·마침표가 낀 숫자도 한 덩어리로 본다(1,234 / 42.0).
        "숫자 4자리 이상이 들어 있음 — 값일 가능성이 높다" to
            Regex("""\d[\d,.]{3,}"""),
    )
}
