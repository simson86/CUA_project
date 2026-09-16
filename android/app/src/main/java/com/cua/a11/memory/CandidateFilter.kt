package com.cua.a11.memory

/**
 * 리플렉터가 뽑은 후보 1건. **문장이 아니라 칸이다**(설계 §2).
 *
 * `trigger` → `consequence` 두 칸뿐인 것이 §3 집행의 A층이다.
 *
 * ⚠️ **명세의 "넣을 자리 자체가 없다"는 과장이다.** 칸을 채우는 것은 모델이고, 코드는
 * *"trigger 는 문자열"* 까지만 강제할 수 있지 *"trigger 에 동작이 들어 있다"* 는 강제하지
 * 못한다. 모델은 얼마든지 `consequence` 에 `"잔액은 1,234,567원이다"` 를 넣을 수 있다.
 * A층이 실제로 하는 일은 **자리를 없애는 게 아니라 덜 주는 것**이다 — 자유 서술 칸이
 * 없으니 모든 것을 동작→결과로 말하게 되고, 그만큼 화면 내용보다 앱 동작 쪽으로 기운다.
 * 유용하지만 **보장은 아니다.** 보장은 아래 결정론적 검사(길이·출처 대조·정규식)에서 온다.
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
 * | A | 구조적 템플릿([Candidate]) | 자유 서술 칸을 없앰 — **보장이 아니라 편향**(위 주석) |
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

    /**
     * 그 실행에 대해 **우리가 아는 사실**. `episode` 에서 만든다.
     *
     * 왜 필요한가 — 후보의 `sourceTurn`·`pkg` 는 **모델이 신고한 값**이다. 그대로 믿으면
     * 규칙 A 가 무너진다: 7번 턴에서 뽑고도 `sourceTurn: 3` 이라고 적으면(악의가 아니라
     * 그냥 틀려도) 오염된 턴 검사를 **조용히 빠져나간다.** *"출처로 자르니 판정이 필요
     * 없다"* 는 규칙 A 의 장점은 **출처가 사실일 때만** 성립한다.
     */
    data class RunFacts(
        /** 이 실행에 실제로 있었던 턴 번호. */
        val turns: Set<Int>,
        /** 그중 `require_confirmation` 이 붙었던 턴(`episode.hadSafety`). */
        val taintedTurns: Set<Int>,
        /** 이 실행에서 실제로 포그라운드였던 패키지. */
        val pkgs: Set<String>,
    )

    // 길이 상한. 값을 욱여넣기 어렵게 만드는 **결정론적** 장치다 — A층이 '자리를 덜 주는'
    // 것이라면 이건 그 자리를 더 좁힌다. 숫자에 근거는 없다(지금 쓰는 기억들이 이 안에
    // 들어온다는 것뿐). 리플렉터를 돌려 보고 조정할 것.
    private const val MAX_TRIGGER = 80
    private const val MAX_CONSEQUENCE = 160

    /** 통과면 null, 아니면 **사람이 읽을 사유**(실행 로그·목록에 그대로 뜬다). */
    fun reject(c: Candidate, facts: RunFacts): String? {
        val taintedTurns = facts.taintedTurns

        // ── 형식 — 모델이 칸을 제대로 채웠나 ────────────────────────────
        if (c.kind !in MemoryGateway.KINDS) return "알 수 없는 종류: ${c.kind}"
        if (c.trigger.isBlank() || c.consequence.isBlank()) return "빈 칸이 있음"
        if (c.trigger.length > MAX_TRIGGER)
            return "trigger 가 너무 김(${c.trigger.length}자) — 값을 욱여넣었을 수 있다"
        if (c.consequence.length > MAX_CONSEQUENCE)
            return "consequence 가 너무 김(${c.consequence.length}자) — 값을 욱여넣었을 수 있다"

        // ── 출처 대조 — 모델이 신고한 값을 episode 와 맞춰 본다 ★ ────────
        //  이게 없으면 규칙 A 가 모델의 자기 신고에 의존한다(위 RunFacts 주석).
        if (c.sourceTurn !in facts.turns)
            return "이 실행에 없는 턴(${c.sourceTurn})을 근거로 댐 — 출처를 확인할 수 없다"
        //  그 실행에서 간 적 없는 앱에 기억을 붙이면, 나중에 그 앱에서 엉뚱한 문장이 뜬다.
        c.pkg?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach {
            if (it !in facts.pkgs) return "이 실행에서 간 적 없는 앱($it)에 붙이려 함"
        }
        // ── 규칙 A — 안전은 학습 대상이 아니다 ──────────────────────────
        //  "저번엔 확인 없이 됐다"가 사실로 승격되면 안전 게이트가 무력화된다.
        //  ★ 내용을 판정하지 않는다. "이게 안전 관련인가"를 문장으로 보려 하면 모델
        //    판단이 끼어들어 비결정적이 된다. 어느 턴에 확인 카드가 떴는지는 우리가
        //    이미 안다(episode.hadSafety) — 그 턴에서 나온 후보를 통째로 버리면
        //    판정이 필요 없다.
        //  ★ 바로 위에서 sourceTurn 이 실재하는 턴임을 확인했기 때문에 이 검사가 의미를
        //    갖는다. 순서를 바꾸지 말 것 — 확인 없이 이 줄만 있으면 모델이 턴 번호를
        //    잘못 적는 것만으로 안전 규칙을 빠져나간다.
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
