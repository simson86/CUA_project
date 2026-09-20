package com.cua.a11.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** turn=1 의 액션 분포 — 설계 문서 부록 F의 Q7("턴 1에 appNote 를 붙일지")을 데이터로 답한다. */
data class ActionCount(val action: String?, val n: Int)

/** 패키지별로 관측된 서로 다른 버전 수 — 앱 UI 변경 주기의 대리 지표(부록 F의 N2). */
data class PkgVersionSpread(val pkg: String, val versions: Int)

/**
 * 기억 하나의 주입 이력 요약 (Unit 6).
 * `runs` 와 `injections` 를 **나눠서** 준다 — numRecalled 는 이 둘을 섞어 놔서
 * "한 실행에서 20번 주입"과 "20개 실행에서 한 번씩"을 구분하지 못한다. 근거의 강도는
 * 앞이 아니라 **뒤**인데 지금 랭킹은 앞을 보고 정렬한다(Unit 7 에서 답할 문제).
 */
data class RecallStat(val memoryId: Long, val runs: Int, val injections: Int, val successes: Int)

/**
 * 전부 **블로킹** 메서드다. runAgent 가 코루틴이 아니라 백그라운드 스레드에서 돌고
 * (CuClient.kt 의 "반드시 백그라운드 스레드에서 호출" 주석 참조) 이 코드베이스에 코루틴이
 * 없으므로, suspend 를 쓰면 호출부에만 스코프가 새로 필요해진다.
 * 메인 스레드에서 부르면 Room 이 예외를 던진다 — 그게 맞는 동작이다.
 */
@Dao
interface MemoryDao {

    // ── 쓰기 ──────────────────────────────────────────────
    @Insert fun insertRun(run: RunEntity)

    @Query("UPDATE run SET outcome = :outcome, turnsUsed = :turnsUsed, endedAt = :endedAt, " +
           "memoryReadFailed = :memoryReadFailed WHERE id = :id")
    fun finishRun(id: String, outcome: String, turnsUsed: Int, endedAt: Long, memoryReadFailed: Boolean)

    @Insert fun insertEpisode(ep: EpisodeEntity)

    // ── 읽기: Unit 1 검증용 ────────────────────────────────
    @Query("SELECT COUNT(*) FROM run") fun runCount(): Int
    @Query("SELECT COUNT(*) FROM episode") fun episodeCount(): Int

    @Query("SELECT COUNT(*) FROM episode WHERE runId = :runId")
    fun episodeCountOf(runId: String): Int

    @Query("SELECT * FROM run ORDER BY startedAt DESC LIMIT :limit")
    fun recentRuns(limit: Int): List<RunEntity>

    @Query("SELECT * FROM episode WHERE runId = :runId ORDER BY turn, id")
    fun episodesOf(runId: String): List<EpisodeEntity>

    // ── 읽기: 열린 질문에 답하는 집계 ──────────────────────
    /** Q7 — 첫 턴이 정말 대부분 list_apps 인가. */
    @Query("SELECT action, COUNT(*) AS n FROM episode WHERE turn = 1 GROUP BY action ORDER BY n DESC")
    fun firstTurnActions(): List<ActionCount>

    // ── 기억 읽기 (Unit 2) ────────────────────────────────
    //  랭킹은 아직 없다. 기억이 몇 개일 때 점수식은 무의미하다 — Unit 7 에서 붙인다.
    //  지금은 하드 필터만 걸고 LIMIT 으로 자른다.
    //  하드 필터가 곧 설계 §5 ②단계다: ACTIVE · 무효화 안 됨 · 만료 전 · 민감하지 않음.

    /**
     * ACTIVE 인 APP_FACT 전체. **어느 앱에 붙일지는 코틀린에서 고른다**(MemoryGateway).
     *
     * 왜 SQL 에서 `pkg = :pkg` 로 안 자르나 — 한 과제가 **여러 패키지를 넘나든다.**
     * 실측 2026-09-16: "저장공간 확인"이 `com.android.settings` →
     * `com.samsung.android.lool`(디바이스 케어) → `com.sec.android.app.myfiles`(저장공간)
     * 로 이동해, `com.android.settings` 에 묶인 기억이 **74턴 중 22턴에만** 붙었다.
     * 기억이 가리키는 곳에 에이전트가 들어가는 순간 그 기억이 사라진 것이다.
     *
     * 그래서 `pkg` 는 이제 **쉼표로 여러 개**를 담을 수 있고, `scope = "system"` 이면
     * 패키지를 안 본다. 그 판정이 SQL 로는 지저분해서 코틀린으로 옮겼다 —
     * PITFALL 키워드 매칭이 이미 같은 이유로 코틀린에 있다(수십 건 규모라 문제없다).
     */
    @Query(
        "SELECT * FROM memory " +
        "WHERE kind = 'APP_FACT' " +
        "  AND state = 'ACTIVE' AND invalidAt IS NULL AND sensitivity = 'normal' " +
        "  AND (expiresAt IS NULL OR expiresAt > :now) " +
        // ★ 정렬은 **코틀린에서** 한다(MemoryGateway.rank, Unit 7). SQL 로 못 하는 항이
        //   있어서다 — version_match 는 '지금 떠 있는 앱의 버전' 을 알아야 하고, recency 는
        //   지수 감쇠다. id 순은 점수가 같을 때의 **결정론적 동점 처리**일 뿐이다.
        "ORDER BY id"
    )
    fun activeAppFacts(now: Long): List<MemoryEntity>

    /**
     * ACTIVE 인 PITFALL 전체. **키워드 매칭은 코틀린에서 한다.**
     * 지금 규모(수십 건)에서 FTS5 를 세우는 건 과하고, 무엇보다 목표 문장을 어떻게 쪼갤지가
     * 아직 안 정해졌다(설계 §5 의 3단계 대책). 커지면 그때 FTS5 로 옮긴다.
     */
    @Query(
        "SELECT * FROM memory " +
        "WHERE kind = 'PITFALL' " +
        "  AND state = 'ACTIVE' AND invalidAt IS NULL AND sensitivity = 'normal' " +
        "  AND (expiresAt IS NULL OR expiresAt > :now) " +
        "ORDER BY id"   // 정렬은 MemoryGateway.rank (위 주석)
    )
    fun activePitfalls(now: Long): List<MemoryEntity>

    /** 주입 후 기록 — 다음 읽기의 순위를 만든다(설계 §5 ⑤단계). */
    @Query("UPDATE memory SET numRecalled = numRecalled + 1, lastAccessed = :now WHERE id IN (:ids)")
    fun markRecalled(ids: List<Long>, now: Long)

    // ── 목록 UI·검증용 ────────────────────────────────────
    @Query("SELECT * FROM memory ORDER BY state, kind, id")
    fun allMemories(): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memory") fun memoryCount(): Int

    @Insert fun insertMemory(m: MemoryEntity): Long

    /** id 로 찾아 통째로 교체. 사용자가 안 건드린 칸까지 다 쓰므로 호출부는 반드시 원본을
     *  `copy()` 해서 넘겨야 한다 — 새로 지어 넘기면 numRecalled·timeAdded 같은 이력이
     *  조용히 초기화된다. */
    @Update fun updateMemory(m: MemoryEntity)

    @Query("DELETE FROM memory WHERE id = :id") fun deleteMemory(id: Long)

    /** 지운 행 수를 돌려준다. run·episode 는 건드리지 않는다 — 그쪽은 기억이 아니라 로그다. */
    @Query("DELETE FROM memory") fun deleteAllMemories(): Int

    /** 기억 읽기가 실패한 실행 — Unit 4 의 측정에서 빼야 하는 것들. */
    @Query("SELECT * FROM run WHERE memoryReadFailed = 1 ORDER BY startedAt DESC")
    fun contaminatedRuns(): List<RunEntity>

    /** N2 — 앱 UI 변경 주기의 대리 지표. 패키지별로 관측된 서로 다른 버전 수. */
    @Query("SELECT pkg, COUNT(DISTINCT pkgVersion) AS versions FROM episode " +
           "WHERE pkg IS NOT NULL GROUP BY pkg ORDER BY versions DESC")
    fun versionSpread(): List<PkgVersionSpread>

    // ── 승격 카운터 (Unit 6) ──────────────────────────────
    //  점수를 움직이는 코드는 **여기 넷뿐이다.** 흩어 놓으면 "실행당 1회"가 어디선가
    //  깨지는데, 그 증상은 조용하다 — 기억이 한 실행 만에 ACTIVE 로 올라가 버린다.

    /** 주입 기록. 실행이 끝날 때 한 번에 쓴다 — 그래야 runId 가 확정돼 있다. */
    @Insert fun insertRecalls(rows: List<MemoryRecallEntity>)

    /**
     * 성공한 실행에 주입됐다 → `score + 1`, **상한 4**(명세 §7 개정 3).
     *
     * 상한이 필요한 이유는 랭킹이다 — 없으면 매일 쓰는 앱의 기억이 무한정 부풀어
     * 갓 승격된 기억과 영영 같은 줄에 설 수 없다.
     *
     * `pinned` 도 올린다. 면제 대상은 **강등**이지 승격이 아니다 — 사람이 고정한 기억이
     * 실전에서 통했다면 그 사실은 그대로 기록돼야 한다.
     */
    @Query("UPDATE memory SET score = MIN(score + 1, 4) WHERE id IN (:ids)")
    fun raiseScore(ids: List<Long>): Int

    /**
     * 실패한 실행에 주입됐다 → `score - 1`.
     *
     * ★ **`pinned` 를 보지 않는다**(2026-09-16 변경). 원칙: **코드가 재는 것은 사람 것에도
     * 적용하고, 모델이 판정하는 것만 면제한다.** `pinned` 의 원래 의도는 Unit 8 의 *시간 기반*
     * 정리로부터 보호하는 것이지 *증거 기반* 강등까지 막는 게 아니었다.
     *
     * 그리고 막으면 **`HUMAN_SCORE = 2` 가 무의미해진다** — 목록 UI 가 새 기억에 `pinned` 를
     * 기본 체크하므로 사람이 쓴 기억은 강등 자체가 안 걸렸다(그래서 `bench` 기억에만 유효했다).
     * **사람도 틀린다** — 2차 측정에서 턴을 +27% 늘린 메타클럽 문장은 사람이 쓴 것이다.
     */
    @Query("UPDATE memory SET score = score - 1 WHERE id IN (:ids)")
    fun lowerScore(ids: List<Long>): Int

    /**
     * 검역 해제: PENDING 이 `score >= 2` 가 되면 ACTIVE.
     *
     * **"2회"는 반드시 실행 간이다**(명세 §7). 그래서 이 갱신은 실행이 끝날 때 한 번만
     * 불려야 하고, 주입 지점(markRecalled)에 걸면 안 된다 — 거기 걸면 한 실행의
     * 두 턴만으로 검역이 풀린다.
     */
    @Query("UPDATE memory SET state = 'ACTIVE' " +
           "WHERE id IN (:ids) AND state = 'PENDING' AND score >= 2")
    fun activateProven(ids: List<Long>): Int

    /**
     * 소진: `score <= 0` 이면 RETIRED + `invalidAt` 기록.
     * **무효화는 삭제가 아니다** — 사람이 목록에서 보고 되살릴 수 있어야 한다.
     */
    @Query("UPDATE memory SET state = 'RETIRED', invalidAt = :now " +
           "WHERE id IN (:ids) AND state != 'RETIRED' AND score <= 0")
    fun retireExhausted(ids: List<Long>, now: Long): Int

    /**
     * 이 실행에 **주입됐던 기억**. 리플렉터(Unit 5b)의 세 번째 입력이다.
     *
     * 설계 §2 가 입력을 셋으로 잡는데 이게 빠지면 *"리플렉터가 이미 아는 것을 매번 새로
     * 발견한다"*. `memory_recall` 이 write-only 를 벗어나는 지점이기도 하다.
     *
     * 사람이 그 기억을 지웠으면 조인에서 빠진다 — 외래키가 없어 고아 행이 남기 때문인데,
     * 리플렉터 입장에선 그게 맞다(없는 기억을 "이미 안다"고 알려줄 이유가 없다).
     */
    @Query("SELECT m.* FROM memory m JOIN memory_recall r ON r.memoryId = m.id " +
           "WHERE r.runId = :runId ORDER BY m.id")
    fun injectedIn(runId: String): List<MemoryEntity>

    // ── 용량 상한 (Unit 8) ────────────────────────────────
    @Query("SELECT COUNT(*) FROM memory WHERE state = 'ACTIVE'")
    fun activeCount(): Int

    /** 강등 후보. `pinned` 는 애초에 빼고 가져온다 — 사람이 고정한 것은 상한으로 안 내린다. */
    @Query("SELECT * FROM memory WHERE state = 'ACTIVE' AND pinned = 0")
    fun activeUnpinned(): List<MemoryEntity>

    /** 삭제가 아니라 **검역으로 되돌린다** — 사람이 목록에서 볼 기회를 남긴다(명세 §7). */
    @Query("UPDATE memory SET state = 'PENDING' WHERE id IN (:ids) AND state = 'ACTIVE'")
    fun demoteToPending(ids: List<Long>): Int

    @Query("SELECT COUNT(*) FROM memory WHERE state = 'PENDING'")
    fun pendingCount(): Int

    /**
     * 버릴 수 있는 검역 후보.
     *  · `pinned` 제외 — 사람이 고정한 것
     *  · **`supersededBy` 로 참조되는 행 제외** — `UPDATE` 판정이 만든 새 행이 여기 올 수
     *    있는데, 지우면 옛 기억이 가리키는 곳이 사라져 **버전 체인이 끊긴다**(명세가
     *    `RETIRED` 에 대해 *"superseded_by 로 참조되면 유지"* 라고 한 것과 같은 이유).
     */
    @Query("SELECT * FROM memory WHERE state = 'PENDING' AND pinned = 0 " +
           "  AND id NOT IN (SELECT supersededBy FROM memory WHERE supersededBy IS NOT NULL)")
    fun pendingDeletable(): List<MemoryEntity>

    @Query("DELETE FROM memory WHERE id IN (:ids)")
    fun deleteMemories(ids: List<Long>): Int

    // ── reconciliation (Unit 5c) ──────────────────────────
    /**
     * 대조 대상 후보. **`RETIRED` 는 뺀다** — 부활 경로를 만들면 불안정하고, 같은 사실이
     * 재관측되면 새 행으로 처음부터 쌓이는 편이 낫다(증거를 다시 모은다).
     *
     * 어느 기억이 이 실행과 관련 있는지는 **코틀린에서** 고른다(`MemoryGateway.reconcileSet`) —
     * `APP_FACT` 는 쉼표 패키지 목록, `PITFALL` 은 목표 키워드라 SQL 로는 지저분하다.
     */
    @Query("SELECT * FROM memory WHERE state != 'RETIRED' ORDER BY id")
    fun reconcilable(): List<MemoryEntity>

    /**
     * `DELETE` 판정 — **즉시 `RETIRED`**. `pinned` 는 면제한다: 모델 판정으로 사람이 쓴
     * 기억을 뒤집지 않는다(뒤집으려면 사람이 목록에서 한다).
     *
     * 되돌릴 수 없어 보이지만 **자가 치유된다** — `RETIRED` 는 대조 대상에서 빠지므로,
     * 같은 사실이 재관측되면 모델은 그런 기억이 있는지도 모른 채 `ADD` 를 낸다. 잃는 것은
     * 누적된 점수뿐이다.
     */
    @Query("UPDATE memory SET state = 'RETIRED', invalidAt = :now " +
           "WHERE id = :id AND state != 'RETIRED' AND pinned = 0")
    fun retireByVerdict(id: Long, now: Long): Int

    /** `UPDATE` 판정 — 기존을 은퇴시키고 새 행을 가리킨다. `pinned` 면제는 위와 같다. */
    @Query("UPDATE memory SET state = 'RETIRED', invalidAt = :now, supersededBy = :newId " +
           "WHERE id = :id AND state != 'RETIRED' AND pinned = 0")
    fun supersede(id: Long, newId: Long, now: Long): Int

    /**
     * 목록 UI·측정용 요약. `run` 을 조인해 성공 수까지 센다.
     * ⚠️ 별칭을 `rn` 으로 둔 건 습관이 아니다 — 이 DB 에서 `action` 이 예약어라
     * `AS action` 이 파싱 실패한 전례가 있다. 짧고 안전한 이름으로 둔다.
     */
    @Query("SELECT r.memoryId AS memoryId, COUNT(*) AS runs, SUM(r.injections) AS injections, " +
           "SUM(CASE WHEN rn.outcome = 'success' THEN 1 ELSE 0 END) AS successes " +
           "FROM memory_recall r JOIN run rn ON rn.id = r.runId GROUP BY r.memoryId")
    fun recallStats(): List<RecallStat>
}
