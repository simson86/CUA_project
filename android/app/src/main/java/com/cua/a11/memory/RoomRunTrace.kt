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
    /**
     * 실행이 끝났음을 알리는 훅 (Unit 5b 리플렉터).
     *
     * **여기 거는 이유** — `runAgent` 의 `finally` 가 부르는 이 지점이 앱 UI·소켓 두 경로가
     * 모두 지나는 **유일한 공통 자리**다. 호출부마다 붙이면 새 실행 경로를 만들 때 빠뜨린다
     * (소켓 `RUN` 이 `cancel` 을 안 넘겨 중단이 아예 안 됐던 일이 그 예다).
     *
     * ⚠️ **이 훅은 즉시 반환해야 한다.** 여기서 리플렉터를 그대로 돌리면 소켓 응답이
     * 그만큼 늦어져 측정 벽시계가 오염되고, `agentBusy` 도 그동안 잡혀 있어 다음 `RUN` 이
     * "이미 실행 중입니다" 로 거절된다. 구현은 큐에 넣기만 한다(a11service).
     */
    private val onRunFinished: (String) -> Unit = {},
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
     *
     * ★ **swallow 를 둘로 나눈 건 의도적이다.** 한 블록에 묶으면 `finishRun` 이 실패했을 때
     * `settleRun` 이 아예 안 불리고, 그러면 게이트웨이의 주입 버퍼가 안 비워져 **이번 실행의
     * 기억이 다음 실행의 결과로 채점된다.** 실패를 삼키는 코드일수록 무엇이 건너뛰어지는지를
     * 봐야 한다.
     */
    override fun onRunEnd(runId: String, outcome: String, turnsUsed: Int) {
        swallow {
            dao.finishRun(runId, outcome, turnsUsed, System.currentTimeMillis(),
                gateway.takeFailureForRun())
        }
        // 승격 카운터(Unit 6). run 행이 갱신된 뒤에 부른다 — recallStats 가 outcome 을
        // 조인해 읽으므로 순서가 뒤집히면 방금 끝난 실행이 집계에서 빠진다.
        swallow { gateway.settleRun(runId, outcome) }
        // 리플렉터(Unit 5b)는 **맨 마지막**이다. settleRun 이 memory_recall 을 쓴 뒤라야
        // injectedIn(runId) 가 "이 실행에 주입됐던 기억" 을 돌려준다 — 리플렉터의 세 번째
        // 입력이고, 없으면 이미 아는 것을 매번 새로 발견한다.
        swallow { onRunFinished(runId) }
    }

    private inline fun swallow(body: () -> Unit) {
        try { body() } catch (e: Exception) { Log.w("a11mem", "trace 실패(무시)", e) }
    }
}
