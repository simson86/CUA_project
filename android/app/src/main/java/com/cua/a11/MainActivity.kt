package com.cua.a11

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val input = findViewById<EditText>(R.id.taskInput)
        val runBtn = findViewById<Button>(R.id.runBtn)
        val result = findViewById<TextView>(R.id.resultView)

        runBtn.setOnClickListener {
            val task= input.text.toString().trim()
            if(task.isEmpty()){
                result.text = "지시사항을 입력하세요. 예 : 설정 앱을 열어줘"
                return@setOnClickListener
            }
            val svc =a11service.instance
            if (svc == null){
                //접근성 서비스 안켜져 있으면 안내 + 설정화면으로
                result.text = "접근성 서비스가 꺼져 있습니다. \n설정 > 접근성에서 'Android_run'을 켠 뒤 다시 실행하세요."
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener

            }
            runBtn.isEnabled = false
            result.text = "실행중... ($task)"
            thread{
                val r = try{
                    svc.runTask(task)
                }catch(e: Exception){
                    "오류: ${e.message}"
                }
                runOnUiThread {
                    result.text = r
                    runBtn.isEnabled = true
                }
            }
        }
    }
}