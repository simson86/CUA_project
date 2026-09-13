package com.cua.a11.memory

import android.util.Log
import com.cua.a11.RunTrace
import com.cua.a11.TurnRecord

/**
 * RunTrace 의 Room 구현. runAgent 는 이 클래스도 Room 도 모르고 RunTrace 인터페이스만 안다.
 *
 * **모든 쓰기를 삼킨다.** 로깅이 실행을 망가뜨리면 안 된다 — 디스크가 가득 찼든 스키마가
 * 어긋났든 에이전트는 계속 돌아야 한다. 실패는 logcat 에만 남긴다.
 */
class RoomRunTrace(
    private val dao: MemoryDao,
    /** 읽기 실패 깃발의 주인. 종료 시 한 번 가져가 run 행에 남긴다. */
    private val gateway: MemoryGateway,
) : RunTrace {

    override fun onRunStart(
        runId: String, task: String, model: String, thinking: String, maxTurns: Int,
    ) = swallow {
        dao.insertRun(
            RunEntity(
                id = runId, goal = task, model = model, thinking = thinking,
                maxTurns = maxTurns, outcome = null, turnsUsed = null,
                startedAt = System.currentTimeMillis(), endedAt = null,
            )
        )
    }

    override fun onTurn(runId: String, rec: TurnRecord) = swallow {
        dao.insertEpisode(
            EpisodeEntity(
                runId = runId, turn = rec.turn,
                pkg = rec.pkg, pkgVersion = rec.pkgVersion,
                action = rec.action, intent = rec.intent, result = rec.result,
                hadSafety = rec.hadSafety, note = rec.note,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 깃발은 **여기서 가져가며 지운다**(시작이 아니라 종료에서). 시작에서 지우면
     * `runAgent` 가 `onRunStart` 보다 **먼저** 부르는 `taskNote` 의 실패가 지워진다.
     */
    override fun onRunEnd(runId: String, outcome: String, turnsUsed: Int) = swallow {
        dao.finishRun(runId, outcome, turnsUsed, System.currentTimeMillis(),
            gateway.takeFailureForRun())
    }

    private inline fun swallow(body: () -> Unit) {
        try { body() } catch (e: Exception) { Log.w("a11mem", "trace 실패(무시)", e) }
    }
}
