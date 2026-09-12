package com.cua.a11.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

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

    @Query("UPDATE run SET outcome = :outcome, turnsUsed = :turnsUsed, endedAt = :endedAt WHERE id = :id")
    fun finishRun(id: String, outcome: String, turnsUsed: Int, endedAt: Long)

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

    /** N2 — 앱 UI 변경 주기의 대리 지표. 패키지별로 관측된 서로 다른 버전 수. */
    @Query("SELECT pkg, COUNT(DISTINCT pkgVersion) AS versions FROM episode " +
           "WHERE pkg IS NOT NULL GROUP BY pkg ORDER BY versions DESC")
    fun versionSpread(): List<PkgVersionSpread>
}
