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

    @Query(
        "SELECT * FROM memory " +
        "WHERE kind = 'APP_FACT' AND pkg = :pkg " +
        "  AND state = 'ACTIVE' AND invalidAt IS NULL AND sensitivity = 'normal' " +
        "  AND (expiresAt IS NULL OR expiresAt > :now) " +
        "ORDER BY numRecalled DESC, id " +
        "LIMIT :limit"
    )
    fun activeForApp(pkg: String, now: Long, limit: Int): List<MemoryEntity>

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
        "ORDER BY numRecalled DESC, id"
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
}
