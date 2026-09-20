package com.cua.a11.memory

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cua.a11.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 기억 목록 UI (Unit 3) — 폰 단독으로 기억을 **보고 고치고 지우는 유일한 창구**.
 *
 * 왜 자동 쓰기(Unit 5)보다 먼저인가: 순서가 반대면 리플렉터가 쌓아 놓은 것에 사용자가
 * 손쓸 방법이 없는 기간이 생긴다. 지우는 길이 먼저 있어야 넣기 시작할 수 있다.
 *
 * Database Inspector 로도 같은 표를 볼 수 있지만 그건 **개발 도구지 제품이 아니다** —
 * PC·안드로이드 스튜디오·USB 가 있어야 한다. 이 화면은 폰만으로 된다.
 *
 * 설계: docs/reference/android_run-memory-2026-09-12.html §8(사람의 개입)
 */
class MemoryActivity : AppCompatActivity() {

    // a11service 와 같은 Room 싱글턴 위에 게이트웨이만 하나 더 얹는다.
    // (MemoryDb.get 이 인스턴스를 하나로 유지하므로 DB 연결이 둘이 되지는 않는다.)
    private val gateway by lazy { MemoryGateway(MemoryDb.get(this).dao()) }

    private var rows: List<MemoryEntity> = emptyList()
    /** 기억별 주입 이력(Unit 6). 목록과 함께 한 번에 읽는다. */
    private var stats: Map<Long, RecallStat> = emptyMap()
    /** 배운 뒤에 앱이 업데이트된 기억의 id (Unit 8 버전 태깅). */
    private var outdated: Set<Long> = emptySet()
    private lateinit var adapter: RowAdapter

    private lateinit var listView: ListView
    private lateinit var summary: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)

        listView = findViewById(R.id.memList)
        summary = findViewById(R.id.memSummary)
        empty = findViewById(R.id.memEmpty)
        adapter = RowAdapter()
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, pos, _ -> showEditor(rows[pos]) }

        // 자동 추출 스위치 (Unit 5b). 여기 둔 이유 — 기억을 보는 화면과 기억이 생기는
        // 규칙을 같은 자리에서 다루는 게 맞다. 켜면 그 결과가 바로 이 목록에 PENDING 으로
        // 쌓이므로, 켠 사람이 결과를 보는 화면도 여기다.
        findViewById<CheckBox>(R.id.memReflector).apply {
            isChecked = MemoryGateway.reflectorEnabled(this@MemoryActivity)
            setOnCheckedChangeListener { _, on ->
                MemoryGateway.setReflectorEnabled(this@MemoryActivity, on)
                Toast.makeText(
                    this@MemoryActivity,
                    if (on) "자동 추출 켜짐 — 후보는 검토 대기(PENDING)로 쌓입니다"
                    else "자동 추출 꺼짐",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        findViewById<Button>(R.id.memAddBtn).setOnClickListener { showEditor(null) }
        findViewById<Button>(R.id.memClearBtn).setOnClickListener { confirmClearAll() }

        reload()
    }

    /**
     * 다른 화면에서 실행이 돌면 numRecalled 가 오른다. 돌아왔을 때 낡은 숫자가 남아 있으면
     * "정말 주입됐나"를 이 화면으로 확인할 수 없으므로 매번 다시 읽는다.
     */
    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) reload()
    }

    // ── 데이터 ────────────────────────────────────────────────────────
    /** Room 은 메인 스레드에서 부르면 예외를 던진다 — 읽기도 반드시 백그라운드에서. */
    private fun reload() = io({
        val loaded = gateway.list()
        Triple(loaded, gateway.recallStats(), outdatedOf(loaded))
    }) { (loaded, st, old) ->
        rows = loaded
        stats = st
        outdated = old
        adapter.notifyDataSetChanged()
        val active = loaded.count { it.state == "ACTIVE" }
        // '한 번도 안 걸린 것'을 요약에 올린다. 그 수가 크면 기억이 없는 게 아니라
        // **검색 키(pkg·keywords)가 잘못 잡힌** 것일 가능성이 높다.
        val never = loaded.count { it.state == "ACTIVE" && it.numRecalled == 0 }
        summary.text = buildString {
            append("총 ${loaded.size}건 (ACTIVE ${active}건")
            if (never > 0) append(", 그중 ${never}건은 아직 안 걸림")
            // 사람이 확인할 거리를 요약줄에 올린다 — 명세의 "확인 필요 N건" 배지 자리다.
            val stale = loaded.count { it.id in outdated && it.state == "ACTIVE" }
            if (stale > 0) append(", ${stale}건은 앱이 업데이트됨")
            append(") · 항목을 누르면 고칠 수 있습니다")
        }
        empty.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * 백그라운드에서 [work], 결과를 UI 스레드에서 [then].
     *
     * **실패를 삼키지 않는다.** MemoryGateway 의 읽기 경로는 일부러 조용하지만(에이전트는
     * 기억이 없어도 돌아야 하므로), 사람이 저장을 눌렀는데 조용히 실패하면 저장된 줄 알고
     * 화면을 뜬다. 여기서는 토스트로 반드시 드러낸다.
     */
    private fun <T> io(work: () -> T, then: (T) -> Unit) {
        thread {
            try {
                val r = work()
                runOnUiThread { then(r) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ── 목록 ──────────────────────────────────────────────────────────
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = rows[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: LayoutInflater.from(this@MemoryActivity)
                .inflate(R.layout.item_memory, parent, false)
            val m = rows[position]

            // 점수는 Unit 6 부터 **자동으로 움직인다.** 화면에 없으면 사용자는 상태가
            // 왜 바뀌었는지 알 수 없다(PENDING → ACTIVE 는 score >= 2 에서 일어난다).
            val head = StringBuilder("${m.kind} · ${m.state} · 점수 ${m.score}")
            if (m.pinned) head.append(" · 고정")
            if (m.sensitivity != "normal") head.append(" · ${m.sensitivity}")
            // 주입을 막는 사유는 목록에서 바로 보여야 한다. 안 그러면 사용자는 ACTIVE 인데
            // 왜 안 나오는지 알 길이 없다.
            if (m.invalidAt != null) head.append(" · 무효화됨")
            if (m.id in outdated) head.append(" · 앱 업데이트됨")
            v.findViewById<TextView>(R.id.rowHead).text = head

            v.findViewById<TextView>(R.id.rowText).text = m.text

            // 범위가 pkg 가 아니면 보여준다 — 왜 엉뚱한 앱에서 뜨는지 알 수 있어야 한다.
            val key = when {
                m.kind == "PITFALL" -> "키워드 ${m.keywords ?: "-"}"
                m.scope == "system" -> "모든 앱 (system)"
                else -> m.pkg ?: "패키지 없음"
            }
            v.findViewById<TextView>(R.id.rowMeta).text =
                "$key · ${m.source} · ${fmtDate(m.timeAdded)} 추가"

            // lastAccessed 는 **주입된** 시각이다(목록에서 열어본 시각이 아니다).
            // 0회가 오래 유지되면 검색 키를 의심해야 한다 — 다만 "그 상황이 아직
            // 안 왔다"와는 구분되지 않으므로 판정이 아니라 신호로만 쓴다.
            v.findViewById<TextView>(R.id.rowRecall).text = when {
                m.state != "ACTIVE" -> "주입 대상 아님 (${m.state})"
                m.numRecalled == 0 -> "아직 한 번도 안 걸림 — 검색 키를 확인해 보세요"
                else -> buildString {
                    append("주입 ${m.numRecalled}회")
                    // numRecalled 는 주입 '횟수'라, 한 실행에서 20번 붙은 것과 20개 실행에
                    // 한 번씩 붙은 것이 같은 숫자가 된다. 근거의 강도는 실행 수 쪽이다.
                    stats[m.id]?.let { append(" · ${it.runs}개 실행(성공 ${it.successes})") }
                    append(" · 마지막 ${m.lastAccessed?.let { t -> fmtDate(t) } ?: "?"}")
                }
            }
            return v
        }
    }

    /**
     * **배운 뒤에 앱이 업데이트된 기억**을 고른다 (Unit 8 버전 태깅).
     *
     * 명세의 무효화 4번 — *"`longVersionCode` 변경 → 랭킹 감점 + 사용자 확인 요청"*.
     * **감점은 이미 랭킹이 하고 있다**(`version_match` 0.4, Unit 7). 여기는 **사람에게
     * 보여주는 쪽**이다. 즉시 강등하지 않는 것도 명세 그대로다 — *"마이너 업데이트는 대개
     * UI 를 안 바꾸는데 매번 전부 날리게 된다."* 판단은 사람이 한다(D층).
     *
     * 명세는 확인 버튼·확인 후 감점 해제까지 말하지만 **표시만** 한다 — 한 달짜리
     * 프로젝트라 흐름을 만드는 값어치가 없다. 사람은 보고 지우거나 두면 된다.
     *
     * `pkg` 가 쉼표 목록이면 **그중 하나라도 버전이 같으면 최신**으로 본다. `pkgVersion`
     * 은 리플렉터가 목록 중 버전을 아는 첫 패키지에서 가져온 값이라, 어느 패키지의 것인지
     * 특정할 수 없기 때문이다. `pkgVersion` 이 없는 기억(사람이 쓴 것)은 판정하지 않는다.
     */
    private fun outdatedOf(list: List<MemoryEntity>): Set<Long> {
        val cache = HashMap<String, Long?>()
        fun current(pkg: String): Long? = cache.getOrPut(pkg) {
            try { packageManager.getPackageInfo(pkg, 0).longVersionCode } catch (e: Exception) { null }
        }
        return list.filter { m ->
            val v = m.pkgVersion ?: return@filter false
            val pkgs = m.pkg?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val known = pkgs.mapNotNull { current(it) }
            known.isNotEmpty() && v !in known
        }.map { it.id }.toSet()
    }

    private fun fmtDate(ms: Long) =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    // ── 편집 ──────────────────────────────────────────────────────────
    /** [existing] 가 null 이면 새로 만들기. */
    private fun showEditor(existing: MemoryEntity?) {
        val v = layoutInflater.inflate(R.layout.dialog_memory_edit, null)
        val kindSp = v.findViewById<Spinner>(R.id.edKind)
        val textEd = v.findViewById<EditText>(R.id.edText)
        val pkgRow = v.findViewById<View>(R.id.edPkgRow)
        val pkgEd = v.findViewById<EditText>(R.id.edPkg)
        val kwRow = v.findViewById<View>(R.id.edKeywordsRow)
        val kwEd = v.findViewById<EditText>(R.id.edKeywords)
        val stateSp = v.findViewById<Spinner>(R.id.edState)
        val sensSp = v.findViewById<Spinner>(R.id.edSensitivity)
        val pinnedCb = v.findViewById<CheckBox>(R.id.edPinned)
        val prov = v.findViewById<TextView>(R.id.edProvenance)

        fun fill(sp: Spinner, items: List<String>, want: String?) {
            sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            sp.setSelection(items.indexOf(want).coerceAtLeast(0))
        }
        fill(kindSp, MemoryGateway.KINDS, existing?.kind)
        fill(sensSp, MemoryGateway.SENSITIVITIES, existing?.sensitivity)

        // ── 상태는 '새로 만들 때'와 '고칠 때'의 요구가 정반대다 ────────────────
        //  새 기억: ACTIVE 로 고정한다. 명세 §7 이 **사람의 판단 = 즉시 ACTIVE** 로 못
        //   박았고, PENDING 은 *기계 출력*을 담는 검역이라 사람이 직접 쓴 문장을 거기
        //   넣을 이유가 없다. 게다가 지금은 나가는 문이 없어(승격 = reconciliation,
        //   Unit 5) 고르는 순간 **주입도 승격도 안 되는 행**이 된다.
        //  고칠 때: 세 상태를 모두 연다. 리플렉터가 쌓은 PENDING 후보를 사람이 보고
        //   ACTIVE 로 올리는 것이 설계 §8 이 말한 '아키텍처의 일부'다.
        //
        //  ⚠️ 여기서 MemoryGateway.STATES 자체를 줄이지 말 것. 줄이면 fill() 의
        //   indexOf(...).coerceAtLeast(0) 이 목록에 없는 값을 **0번(ACTIVE)** 으로
        //   되돌려, 사람이 PENDING 후보를 열었다 저장하는 것만으로 **검역이 조용히
        //   풀린다.** 선택지를 좁히는 일은 이 분기에서만 한다.
        if (existing == null) {
            fill(stateSp, listOf("ACTIVE"), "ACTIVE")
            stateSp.isEnabled = false
        } else {
            fill(stateSp, MemoryGateway.STATES, existing.state)
        }

        // 종류에 따라 검색 키가 다르다 — APP_FACT 는 패키지로, PITFALL 은 키워드로 걸린다.
        // 둘 다 보여주면 엉뚱한 칸을 채우고 "저장은 됐는데 안 나온다"가 된다.
        fun syncKeyRow() {
            val isPitfall = kindSp.selectedItem?.toString() == "PITFALL"
            pkgRow.visibility = if (isPitfall) View.GONE else View.VISIBLE
            kwRow.visibility = if (isPitfall) View.VISIBLE else View.GONE
        }
        kindSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, view: View?, pos: Int, id: Long) = syncKeyRow()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        syncKeyRow()

        if (existing != null) {
            textEd.setText(existing.text)
            pkgEd.setText(existing.pkg ?: "")
            kwEd.setText(existing.keywords ?: "")
            pinnedCb.isChecked = existing.pinned
            prov.visibility = View.VISIBLE
            prov.text = buildString {
                append("#${existing.id} · 출처 ${existing.source}")
                existing.sourceRunId?.let { append(" · run ${it.take(8)}") }
                append(" · 점수 ${existing.score} · 주입 ${existing.numRecalled}회")
                existing.pkgVersion?.let { append("\n배울 당시 앱 버전 $it") }
            }
        } else {
            // 사람이 직접 쓴 기억은 기본으로 고정한다 — Unit 8 의 자동 감쇠가 손으로 넣은
            // 것을 조용히 강등시키면 안 된다. 체크는 풀 수 있다.
            pinnedCb.isChecked = true
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "새 기억" else "기억 고치기")
            .setView(v)
            // ★ 리스너를 null 로 달고 아래 setOnShowListener 에서 다시 단다. 여기에 바로
            //   달면 검증에 실패해도 다이얼로그가 닫혀 입력이 통째로 날아간다.
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .apply { if (existing != null) setNeutralButton("삭제", null) }
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val kind = kindSp.selectedItem?.toString() ?: return@setOnClickListener
                val isPitfall = kind == "PITFALL"
                // 안 쓰는 쪽 검색 키는 비워서 저장한다. 종류를 바꿔 저장하면 옛 키가 남아
                // 목록에 유령 정보로 뜬다.
                // 새로 만들 때만 HUMAN_SCORE 로 시작한다. existing 은 copy() 라
                // 지금까지의 점수가 그대로 유지된다 — 편집이 이력을 지우면 안 된다.
                val draft = (existing ?: MemoryEntity(
                    kind = kind, text = "", source = "user_ui",
                    score = MemoryGateway.HUMAN_SCORE,
                    timeAdded = System.currentTimeMillis(),
                )).copy(
                    kind = kind,
                    text = textEd.text.toString().trim(),
                    pkg = if (isPitfall) null else pkgEd.text.toString().trim().ifBlank { null },
                    keywords = if (isPitfall) kwEd.text.toString().trim().ifBlank { null } else null,
                    state = stateSp.selectedItem?.toString() ?: "ACTIVE",
                    sensitivity = sensSp.selectedItem?.toString() ?: "normal",
                    pinned = pinnedCb.isChecked,
                )
                gateway.validate(draft)?.let { why ->
                    AlertDialog.Builder(this).setMessage(why).setPositiveButton("확인", null).show()
                    return@setOnClickListener
                }
                io({ gateway.save(draft) }) {
                    dlg.dismiss()
                    reload()
                }
            }
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dlg.dismiss() }
            if (existing != null) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    confirmDelete(existing) { dlg.dismiss() }
                }
            }
        }
        dlg.show()
    }

    // ── 삭제 ──────────────────────────────────────────────────────────
    private fun confirmDelete(m: MemoryEntity, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("이 기억을 지울까요?")
            .setMessage(m.text)
            .setPositiveButton("삭제") { _, _ ->
                io({ gateway.delete(m.id) }) { onDone(); reload() }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmClearAll() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "지울 기억이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("기억을 전부 지울까요?")
            // 무엇이 남는지 명시한다 — '전부 삭제'가 실행 로그까지 지운다고 오해하기 쉽다.
            .setMessage("${rows.size}건이 사라집니다. 되돌릴 수 없습니다.\n" +
                    "실행 로그(run·episode)는 지워지지 않습니다.")
            .setPositiveButton("전부 삭제") { _, _ ->
                io({ gateway.deleteAll() }) { n ->
                    Toast.makeText(this, "${n}건 삭제", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
