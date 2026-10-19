package io.xr.harness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** MVVM 最小形态自证：StateFlow 单向数据流。骨架期无业务、无数据获取。 */
class MainViewModel {

  private val _greeting = MutableStateFlow("harness skeleton")
  val greeting: StateFlow<String> = _greeting
}
