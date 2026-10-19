package io.xr.harness

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/** 骨架期单屏占位：无业务、无数据获取（API 面等 shared 契约生成物，suite-hard-rules §4）。 */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val vm = MainViewModel()
    setContent {
      MaterialTheme {
        val greeting by vm.greeting.collectAsState()
        Text(text = greeting)
      }
    }
  }
}
